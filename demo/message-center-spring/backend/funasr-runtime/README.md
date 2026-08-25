# FunASR 容器运行说明

该目录是消息中心电话录音转录服务的 Docker 构建上下文。容器固定使用 Python 3.10.20、FunASR 1.4.0 和 `requirements.txt` 中的顶层依赖版本，并从 PyTorch 官方 CPU wheel 仓库安装 `torch` 与 `torchaudio`，避免 CPU 服务混入 CUDA 运行包。`sensevoice` 组合以下模型：

- `iic/SenseVoiceSmall`：语音识别
- `fsmn-vad`：语音活动检测与真实语音区间
- `ct-punc-c`：中英文标点恢复

宿主机不需要安装 Python、FunASR、PyTorch 或 CT-Punc。首次启动需要联网下载模型，模型保存在 Compose 的 `funasr_cache` volume 中，普通重启不会重复下载。

## 启动

在 `demo/message-center-spring/backend` 目录执行：

```bash
docker compose up -d --build funasr
docker compose ps funasr
curl http://127.0.0.1:8000/health
```

如果旧服务仍占用 8000 端口，先确认占用该端口的容器：

```bash
docker ps --filter publish=8000
docker stop <上一步确认的旧容器名>
```

该操作只停止旧容器，不删除旧模型 volume。

## 资源配置

默认档位适合本地低占用运行：

```text
FUNASR_CPU_LIMIT=1.0
FUNASR_INTRAOP_THREADS=1
FUNASR_INTEROP_THREADS=1
FUNASR_MAX_CONCURRENCY=1
```

服务器可以通过 Compose 环境变量增加 CPU、线程和并发。`FUNASR_CPU_LIMIT` 应不小于 `FUNASR_INTRAOP_THREADS`，增加并发会同时增加内存和 CPU 峰值。

其他可配置项：

```text
FUNASR_HOST_PORT=8000
FUNASR_DEVICE=cpu
FUNASR_MODEL=sensevoice
```

FunASR 和 Web 运行依赖由 `requirements.txt` 固定，Compose 不提供版本覆盖项，避免不同服务器构建出不同的转录行为。模型仓库目前仍由 FunASR 别名解析到 ModelScope `master`；正式上线前应在可构建环境中完成新镜像与模型快照验收。

当前镜像安装的是 CPU 版 PyTorch。仅修改 `FUNASR_DEVICE=cuda` 不会获得 GPU 能力；GPU 部署需要单独的 CUDA 基础镜像和对应 PyTorch 构建。

## 测试

项目内运行层测试不需要在宿主机安装 Python 依赖。已有本地镜像时，在后端目录执行：

```bash
docker run --rm \
  -v "$PWD/funasr-runtime:/app:ro" \
  -w /app \
  crm-logistics-message-center-funasr:local \
  python -m unittest -v test_server.py
```

测试覆盖资源配置上界、SenseVoice 控制标签清理、真实毫秒时间戳映射、非法分段过滤、HTTP 请求体和接纳上界、上传大小上界、空文件拒绝和异常路径临时文件释放。
