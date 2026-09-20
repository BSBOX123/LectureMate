# GPU 서버(EC2)용 기본 이미지. CUDA + cuDNN 이 있어야 faster-whisper(CTranslate2)가 GPU 를 쓴다.
# 로컬에서 CPU 로 빌드/테스트하려면:
#   docker build --build-arg BASE_IMAGE=python:3.13-slim -t lecturemate-ai .
ARG BASE_IMAGE=nvidia/cuda:12.4.1-cudnn-runtime-ubuntu22.04
FROM ${BASE_IMAGE}

WORKDIR /app
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1

# CUDA 이미지에는 python 이 없으므로 설치한다 (python:slim 기반이면 이미 있음)
RUN if ! command -v python3 >/dev/null; then \
      apt-get update && apt-get install -y --no-install-recommends python3 python3-pip && \
      rm -rf /var/lib/apt/lists/*; \
    fi

COPY requirements.txt ./
RUN python3 -m pip install --break-system-packages -r requirements.txt

COPY . .

# 모델 캐시는 볼륨으로 붙여 컨테이너를 다시 만들어도 다시 받지 않게 한다
ENV HF_HOME=/models
VOLUME ["/models"]

EXPOSE 8000
CMD ["python3", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
