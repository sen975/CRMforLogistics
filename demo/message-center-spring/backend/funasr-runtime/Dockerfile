FROM python:3.10.20-slim

ARG PIP_VERSION=26.2.1

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    FUNASR_MODEL=sensevoice \
    FUNASR_DEVICE=cpu \
    FUNASR_PORT=8000 \
    FUNASR_INTRAOP_THREADS=1 \
    FUNASR_INTEROP_THREADS=1 \
    FUNASR_MAX_CONCURRENCY=1

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        git \
        libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /app/requirements.txt

RUN python -m pip install "pip==${PIP_VERSION}" \
    && pip install \
        --index-url https://download.pytorch.org/whl/cpu \
        "torch==2.8.0" \
        "torchaudio==2.8.0" \
    && pip install --requirement /app/requirements.txt \
    && python -m pip check

COPY server.py test_server.py /app/

EXPOSE 8000

CMD ["sh", "-c", "python server.py --host 0.0.0.0 --port ${FUNASR_PORT:-8000} --device ${FUNASR_DEVICE:-cpu} --model ${FUNASR_MODEL:-sensevoice}"]
