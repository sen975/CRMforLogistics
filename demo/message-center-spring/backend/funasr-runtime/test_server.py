import asyncio
import io
import os
import tempfile
import unittest
from unittest.mock import patch

from fastapi import HTTPException, UploadFile
from starlette.requests import Request
from starlette.responses import JSONResponse

import server


class FunAsrRuntimeTest(unittest.TestCase):
    def test_validate_request_size_requires_bounded_content_length(self):
        for headers, expected_status in (
            ([], 411),
            ([(b"content-length", b"invalid")], 400),
            ([(b"content-length", str(server.MAX_REQUEST_BYTES + 1).encode())], 413),
        ):
            with self.subTest(headers=headers):
                request = Request(
                    {
                        "type": "http",
                        "method": "POST",
                        "path": "/v1/audio/transcriptions",
                        "headers": headers,
                    }
                )
                with self.assertRaises(HTTPException) as raised:
                    server.validate_transcription_request_size(request)
                self.assertEqual(expected_status, raised.exception.status_code)

    def test_admission_middleware_rejects_request_when_capacity_is_full(self):
        request = Request(
            {
                "type": "http",
                "method": "POST",
                "path": "/v1/audio/transcriptions",
                "headers": [(b"content-length", b"100")],
            }
        )

        async def exercise():
            server.HTTP_ADMISSION_GATE = server.AdmissionGate(1)
            self.assertTrue(await server.HTTP_ADMISSION_GATE.try_acquire())
            try:
                return await server.bound_transcription_requests(
                    request, lambda _: JSONResponse({"unexpected": True})
                )
            finally:
                await server.HTTP_ADMISSION_GATE.release()

        response = asyncio.run(exercise())
        self.assertEqual(503, response.status_code)

    def test_read_bounded_int_accepts_only_configured_range(self):
        with patch.dict(os.environ, {"TEST_LIMIT": "4"}):
            self.assertEqual(4, server.read_bounded_int("TEST_LIMIT", 1, 8))

        for value in ("0", "9", "invalid"):
            with self.subTest(value=value), patch.dict(
                os.environ, {"TEST_LIMIT": value}
            ):
                with self.assertRaises(ValueError):
                    server.read_bounded_int("TEST_LIMIT", 1, 8)

    def test_clean_text_removes_sensevoice_control_tags(self):
        self.assertEqual(
            "你好，world.",
            server.clean_text("<|zh|><|NEUTRAL|>你好，world.<|woitn|>"),
        )

    def test_build_openai_segments_maps_valid_milliseconds_and_skips_invalid_items(self):
        result = {
            "sentence_info": [
                {"start": 0, "end": 1200, "sentence": "第一句。"},
                {"start": 900, "end": 800, "sentence": "倒序时间"},
                {"start": True, "end": 1600, "sentence": "布尔时间"},
                {"start": 1200, "end": 2500, "text": "<|en|>Second."},
                {"start": 2500, "end": 2600, "sentence": ""},
            ]
        }

        self.assertEqual(
            [
                {"start": 0.0, "end": 1.2, "text": "第一句。", "speaker": None},
                {"start": 1.2, "end": 2.5, "text": "Second.", "speaker": None},
            ],
            server.build_openai_segments(result),
        )

    def test_persist_bounded_upload_rejects_empty_file(self):
        upload = UploadFile(filename="empty.wav", file=io.BytesIO(b""))
        with tempfile.NamedTemporaryFile() as target:
            with self.assertRaises(HTTPException) as raised:
                asyncio.run(server.persist_bounded_upload(upload, target.name))

        self.assertEqual(400, raised.exception.status_code)

    def test_persist_bounded_upload_rejects_file_over_limit(self):
        upload = UploadFile(filename="large.wav", file=io.BytesIO(b"12345"))
        with tempfile.NamedTemporaryFile() as target, patch.object(
            server, "MAX_AUDIO_BYTES", 4
        ), patch.object(server, "READ_CHUNK_BYTES", 2):
            with self.assertRaises(HTTPException) as raised:
                asyncio.run(server.persist_bounded_upload(upload, target.name))

        self.assertEqual(413, raised.exception.status_code)

    def test_transcribe_removes_temporary_file_when_upload_is_rejected(self):
        upload = UploadFile(filename="empty.wav", file=io.BytesIO(b""))
        created_paths = []
        original_named_temporary_file = tempfile.NamedTemporaryFile

        def capture_temporary_file(*args, **kwargs):
            temporary_file = original_named_temporary_file(*args, **kwargs)
            created_paths.append(temporary_file.name)
            return temporary_file

        with patch.object(
            server.tempfile, "NamedTemporaryFile", side_effect=capture_temporary_file
        ):
            with self.assertRaises(HTTPException):
                asyncio.run(
                    server.transcribe(
                        upload,
                        model="sensevoice",
                        language=None,
                        response_format="json",
                    )
                )

        self.assertEqual(1, len(created_paths))
        self.assertFalse(os.path.exists(created_paths[0]))

    def test_transcribe_removes_temporary_file_when_upload_close_fails(self):
        class CloseFailingUpload(UploadFile):
            async def close(self):
                raise OSError("close failed")

        upload = CloseFailingUpload(filename="empty.wav", file=io.BytesIO(b""))
        created_paths = []
        original_named_temporary_file = tempfile.NamedTemporaryFile

        def capture_temporary_file(*args, **kwargs):
            temporary_file = original_named_temporary_file(*args, **kwargs)
            created_paths.append(temporary_file.name)
            return temporary_file

        with patch.object(
            server.tempfile, "NamedTemporaryFile", side_effect=capture_temporary_file
        ):
            with self.assertRaises((HTTPException, OSError)):
                asyncio.run(
                    server.transcribe(
                        upload,
                        model="sensevoice",
                        language=None,
                        response_format="json",
                    )
                )

        self.assertEqual(1, len(created_paths))
        self.assertFalse(os.path.exists(created_paths[0]))


if __name__ == "__main__":
    unittest.main()
