"""Bounded OpenAI-compatible FunASR service for call transcription."""

import argparse
import asyncio
import logging
import math
import os
import re
import subprocess
import tempfile
import time
from typing import Optional

import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(title="FunASR OpenAI-Compatible API", version="1.0.0")

MAX_AUDIO_BYTES = 100 * 1024 * 1024
MAX_REQUEST_BYTES = MAX_AUDIO_BYTES + 1024 * 1024
READ_CHUNK_BYTES = 1024 * 1024
SUPPORTED_SUFFIXES = {".wav", ".mp3", ".flac", ".m4a", ".ogg", ".webm"}
MODEL_CONFIGS = {
    "sensevoice": {
        "model": "iic/SenseVoiceSmall",
        "vad_model": "fsmn-vad",
        "punc_model": "ct-punc-c",
        "vad_kwargs": {"max_single_segment_time": 30000},
    },
}

MODEL_REGISTRY = {}
DEVICE = "cpu"
INFERENCE_GATE = asyncio.Semaphore(1)


class AdmissionGate:
    def __init__(self, capacity: int):
        self.capacity = capacity
        self.in_use = 0
        self.lock = asyncio.Lock()

    async def try_acquire(self) -> bool:
        async with self.lock:
            if self.in_use >= self.capacity:
                return False
            self.in_use += 1
            return True

    async def release(self) -> None:
        async with self.lock:
            self.in_use = max(0, self.in_use - 1)


HTTP_ADMISSION_GATE = AdmissionGate(1)
RUNTIME_LIMITS = {
    "intraop_threads": 1,
    "interop_threads": 1,
    "max_concurrency": 1,
}


def read_bounded_int(name: str, default: int, maximum: int) -> int:
    raw = os.getenv(name, str(default))
    try:
        value = int(raw)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{name} must be an integer") from error
    if value < 1 or value > maximum:
        raise ValueError(f"{name} must be between 1 and {maximum}")
    return value


def configure_runtime() -> None:
    global HTTP_ADMISSION_GATE, INFERENCE_GATE, RUNTIME_LIMITS
    intraop_threads = read_bounded_int("FUNASR_INTRAOP_THREADS", 1, 32)
    interop_threads = read_bounded_int("FUNASR_INTEROP_THREADS", 1, 32)
    max_concurrency = read_bounded_int("FUNASR_MAX_CONCURRENCY", 1, 8)

    for name in (
        "OMP_NUM_THREADS",
        "MKL_NUM_THREADS",
        "OPENBLAS_NUM_THREADS",
        "VECLIB_MAXIMUM_THREADS",
        "NUMEXPR_NUM_THREADS",
    ):
        os.environ[name] = str(intraop_threads)

    import torch

    torch.set_num_threads(intraop_threads)
    torch.set_num_interop_threads(interop_threads)
    HTTP_ADMISSION_GATE = AdmissionGate(max_concurrency)
    INFERENCE_GATE = asyncio.Semaphore(max_concurrency)
    RUNTIME_LIMITS = {
        "intraop_threads": intraop_threads,
        "interop_threads": interop_threads,
        "max_concurrency": max_concurrency,
    }


def validate_transcription_request_size(request: Request) -> None:
    content_length = request.headers.get("content-length")
    if content_length is None:
        raise HTTPException(status_code=411, detail="Content-Length is required")
    try:
        request_bytes = int(content_length)
    except ValueError as error:
        raise HTTPException(status_code=400, detail="Content-Length is invalid") from error
    if request_bytes < 1:
        raise HTTPException(status_code=400, detail="Content-Length is invalid")
    if request_bytes > MAX_REQUEST_BYTES:
        raise HTTPException(status_code=413, detail="Request exceeds 101 MiB")


@app.middleware("http")
async def bound_transcription_requests(request: Request, call_next):
    if request.method != "POST" or request.url.path != "/v1/audio/transcriptions":
        return await call_next(request)
    try:
        validate_transcription_request_size(request)
    except HTTPException as error:
        return JSONResponse(status_code=error.status_code, content={"detail": error.detail})
    if not await HTTP_ADMISSION_GATE.try_acquire():
        return JSONResponse(
            status_code=503,
            content={"detail": "Transcription capacity is full"},
            headers={"Retry-After": "1"},
        )
    try:
        return await call_next(request)
    finally:
        await HTTP_ADMISSION_GATE.release()


def load_model(model_name: str):
    if model_name in MODEL_REGISTRY:
        return MODEL_REGISTRY[model_name]
    if model_name not in MODEL_CONFIGS:
        raise ValueError(f"Unknown model '{model_name}'")

    from funasr import AutoModel

    config = MODEL_CONFIGS[model_name].copy()
    config["device"] = DEVICE
    config["disable_update"] = True
    logger.info("Loading model '%s' on %s", model_name, DEVICE)
    started_at = time.time()
    model = AutoModel(**config)
    logger.info("Model '%s' loaded in %.1fs", model_name, time.time() - started_at)
    MODEL_REGISTRY[model_name] = model
    return model


def clean_text(text: str) -> str:
    return re.sub(r"<\|[^|]*\|>", "", text).strip()


def build_openai_segments(result_item: dict) -> list[dict]:
    segments = []
    previous_start = -1.0
    for item in result_item.get("sentence_info", []) or []:
        if not isinstance(item, dict):
            continue
        start = item.get("start")
        end = item.get("end")
        if (
            not isinstance(start, (int, float))
            or isinstance(start, bool)
            or not isinstance(end, (int, float))
            or isinstance(end, bool)
            or not math.isfinite(start)
            or not math.isfinite(end)
            or start < 0
            or end <= start
            or start < previous_start
        ):
            continue
        text = clean_text(str(item.get("sentence") or item.get("text") or ""))
        if not text:
            continue
        segments.append(
            {
                "start": start / 1000.0,
                "end": end / 1000.0,
                "text": text,
                "speaker": item.get("spk"),
            }
        )
        previous_start = start
    return segments


def probe_audio_duration(path: str) -> float:
    completed = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            path,
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=15,
    )
    try:
        duration = float(completed.stdout.strip())
    except (TypeError, ValueError) as error:
        raise ValueError("Audio duration could not be determined") from error
    if not math.isfinite(duration) or duration <= 0 or duration > 7200:
        raise ValueError("Audio duration is outside its bounds")
    return round(duration, 3)


async def persist_bounded_upload(file: UploadFile, path: str) -> None:
    total_bytes = 0
    with open(path, "wb") as target:
        while chunk := await file.read(READ_CHUNK_BYTES):
            total_bytes += len(chunk)
            if total_bytes > MAX_AUDIO_BYTES:
                raise HTTPException(status_code=413, detail="Audio file exceeds 100 MiB")
            target.write(chunk)
    if total_bytes == 0:
        raise HTTPException(status_code=400, detail="Audio file is empty")


@app.post("/v1/audio/transcriptions")
async def transcribe(
    file: UploadFile = File(...),
    model: str = Form(default="sensevoice"),
    language: Optional[str] = Form(default=None),
    response_format: Optional[str] = Form(default="json"),
):
    if model not in MODEL_CONFIGS:
        raise HTTPException(status_code=400, detail="Unsupported model")
    if response_format not in {"json", "verbose_json"}:
        raise HTTPException(status_code=400, detail="Unsupported response format")

    suffix = os.path.splitext(file.filename or "")[1].lower() or ".wav"
    if suffix not in SUPPORTED_SUFFIXES:
        raise HTTPException(status_code=400, detail="Unsupported audio format")

    temporary_path = ""
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temporary_file:
            temporary_path = temporary_file.name
        await persist_bounded_upload(file, temporary_path)

        audio_duration = (
            await asyncio.to_thread(probe_audio_duration, temporary_path)
            if response_format == "verbose_json"
            else None
        )
        generate_kwargs = {"input": temporary_path, "batch_size": 1}
        if language:
            generate_kwargs["language"] = language
        if response_format == "verbose_json":
            generate_kwargs.update(
                {
                    "sentence_timestamp": True,
                    "output_timestamp": True,
                    "return_time_stamps": True,
                }
            )

        async with INFERENCE_GATE:
            result = await asyncio.to_thread(load_model(model).generate, **generate_kwargs)
        if not result or not isinstance(result[0], dict):
            raise ValueError("FunASR returned an invalid result")
        text = clean_text(str(result[0].get("text") or ""))
        if not text:
            raise ValueError("FunASR returned empty text")

        if response_format == "verbose_json":
            return JSONResponse(
                {
                    "text": text,
                    "segments": build_openai_segments(result[0]),
                    "language": language or "auto",
                    "duration": audio_duration,
                    "model": model,
                }
            )
        return JSONResponse({"text": text})
    except HTTPException:
        raise
    except (subprocess.SubprocessError, ValueError) as error:
        logger.warning("Transcription input or result rejected: %s", error)
        raise HTTPException(status_code=422, detail="Audio could not be transcribed") from error
    except Exception as error:
        logger.exception("Transcription failed")
        raise HTTPException(status_code=500, detail="Transcription failed") from error
    finally:
        try:
            await file.close()
        except Exception:
            logger.warning("Uploaded file cleanup failed", exc_info=True)
        finally:
            if temporary_path:
                try:
                    os.unlink(temporary_path)
                except FileNotFoundError:
                    pass


@app.get("/v1/models")
async def list_models():
    return JSONResponse(
        {
            "object": "list",
            "data": [
                {
                    "id": name,
                    "object": "model",
                    "created": 1700000000,
                    "owned_by": "funasr",
                    "ready": name in MODEL_REGISTRY,
                }
                for name in MODEL_CONFIGS
            ],
        }
    )


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "device": DEVICE,
        "models_loaded": list(MODEL_REGISTRY.keys()),
        "models_available": list(MODEL_CONFIGS.keys()),
        "runtime_limits": RUNTIME_LIMITS,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="FunASR OpenAI-compatible API")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--device", default="cpu", choices=("cpu", "cuda", "mps"))
    parser.add_argument("--model", default="sensevoice", choices=tuple(MODEL_CONFIGS))
    args = parser.parse_args()

    global DEVICE
    DEVICE = args.device
    configure_runtime()
    load_model(args.model)
    logger.info("FunASR API listening on %s:%s", args.host, args.port)
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
