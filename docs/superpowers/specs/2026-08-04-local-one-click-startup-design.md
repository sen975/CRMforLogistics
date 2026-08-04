# 本地消息中心与 FunASR 一键启动设计

## 目标

为 `demo/message-center-demo` 提供一个 PowerShell 一键入口，同时运行本机 Python/ModelScope FunASR 服务和消息中心 web。入口复用已经运行的 FunASR，只有在健康检查失败时才启动新的 FunASR 子进程。

## 当前运行合同

- 消息中心工作目录为 `demo/message-center-demo`。
- 消息中心绑定 `127.0.0.1:18099`，避免与当前机器上其他 `8099` 服务的 IPv6 监听冲突。
- 本地数据目录为当前项目下的 `data-local`。
- FunASR 绑定 `127.0.0.1:8000`，使用 `sensevoice` 模型和 ModelScope hub。
- 消息中心通过 `FUNASR_BASE_URL=http://127.0.0.1:8000` 调用 FunASR 的 `/v1/audio/transcriptions`。
- FunASR 健康检查使用 `GET /health`。

## 一键入口

新增 `demo/message-center-demo/start-local.ps1`。脚本从仓库相对路径计算消息中心目录和默认 FunASR 源码目录，允许通过参数或环境变量覆盖 Python 命令、FunASR 根目录、web 端口、FunASR 端口和设备。默认设备为 `cpu`，不假设本机存在 CUDA。

脚本按以下顺序运行：

1. 检查 Python 可执行文件、FunASR 根目录和 Maven；缺失时立即返回带有修复提示的非零退出码。
2. 请求 `http://127.0.0.1:8000/health`。健康检查成功时标记 FunASR 为外部进程，不接管其生命周期。
3. 健康检查失败时，从 FunASR 根目录启动 `python -m funasr.bin.server --host 127.0.0.1 --port 8000 --device cpu --model sensevoice --hub ms`，保存子进程 ID，并在有界时间内轮询健康检查。
4. 健康检查通过后，在 Maven 子进程环境中设置本地运行变量：
   `LOCAL_DEV_MODE=true`、`WEB_BIND_ADDRESS=127.0.0.1`、`WEB_PORT=18099`、`DATA_DIR=<message-center>/data-local`、`LOCAL_WECOM_DATA_SOURCE=fixture`、`LOCAL_WECOM_TARGET_FILE=<data-local>/local-wecom-messages.jsonl`、`CALL_RECORD_DATA_DIR=<data-local>/call-records`、`FUNASR_BASE_URL=http://127.0.0.1:8000` 和 `FUNASR_MODEL=sensevoice`。
5. 前台运行 `mvn -q exec:java "-Dexec.args=web"`，让用户直接看到消息中心和后台转录日志。
6. 捕获 Ctrl+C、脚本异常和 Maven 退出，在脚本确实启动过 FunASR 时停止该子进程；外部已有的 FunASR 不得被停止。

## 失败和边界

- FunASR 健康检查等待有固定上限；超时后停止脚本创建的进程并返回明确错误，不让 Maven 在 FunASR 未就绪时启动。
- `8000` 或 `18099` 被占用时，脚本返回端口错误和建议的覆盖参数，不杀死未知进程。
- 脚本不自动安装 Python、PyTorch、FunASR、ModelScope 或 Maven 依赖；依赖安装属于环境准备，不应在启动时产生不可预期的网络和磁盘副作用。
- 脚本不覆盖 `.env`，也不读取或打印其中的 SMTP、阿里云、企业微信密钥。
- FunASR 的模型下载和首次加载日志保留在 FunASR 子进程输出中；消息中心只有在健康检查通过后才启动。
- 本设计只覆盖 PowerShell 入口，不恢复 Docker Compose FunASR 服务，也不改变 Java 的 FunASR 协议、队列、状态机或音频边界。

## 验收

- 使用已运行的 FunASR 启动脚本时，不创建第二个 FunASR 进程，消息中心可访问 `http://127.0.0.1:18099`。
- 未运行 FunASR 时，脚本启动本机服务，健康检查返回成功后再启动消息中心。
- FunASR 启动失败、健康检查超时、Python 缺失和端口冲突均返回非零状态并清理脚本创建的进程。
- Ctrl+C 后消息中心和脚本创建的 FunASR 均退出，脚本启动前已经存在的 FunASR 仍在运行。
- Java 编译、现有测试和针对性 PowerShell 脚本检查通过。
