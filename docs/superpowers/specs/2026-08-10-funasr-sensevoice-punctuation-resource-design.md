# FunASR SenseVoice 标点分段与可调 CPU 设计

**日期：** 2026-08-10
**状态：** 已确认
**适用范围：** `/Users/z/FunASR/examples/openai_api/` 与 `demo/message-center-spring/` 电话录音自动转录

## 1. 问题与目标

当前 Spring 电话录音链路使用 `sensevoice` 自动转录。FunASR 返回完整 `text`，但未返回 `sentence_info`，因此 Spring 只能保存完整原文，详情页没有可读的标点分段和时间轴。

本轮目标只有两项：

- 保留 SenseVoice 的中文及中英混合识别能力，为转录文本恢复标点并按标点换行。
- 每个展示分段只使用模型或 VAD 提供的真实音频时间边界，禁止按字符数、文本长度或平均语速估算时间。

同时，FunASR 使用 Docker Compose 在 Mac 开发机和 Linux 服务器长期运行。CPU 使用必须可配置：开发机允许低占用慢速运行，生产服务器可以增加核心数换取转录速度，并由容器 CPU limit 提供硬上限。

## 2. 当前证据

`/Users/z/FunASR/examples/openai_api/server.py` 当前配置为：

```python
"sensevoice": {
    "model": "iic/SenseVoiceSmall",
    "vad_model": "fsmn-vad",
    "vad_kwargs": {"max_single_segment_time": 30000},
}
```

请求只传入 `input` 和 `batch_size=1`，没有启用句级时间输出。服务端只有结果包含 `sentence_info` 时才生成 OpenAI 兼容的 `segments`。

当前 FunASR 源码已经具备所需能力：

- `AutoModel.inference_with_vad` 依次执行 VAD、ASR、时间戳合并和标点恢复。
- `punc_model="ct-punc-c"` 可恢复中英文标点；该 CT-Transformer 版本约 291MB，适合 CPU 常驻部署。
- `sentence_timestamp=True` 可生成 `sentence_info`。
- 有可对齐的模型时间戳时，句子使用对齐后的真实时间。
- 标点对齐失败且仍有可靠 VAD 边界时，源码已有按 VAD 区间回退的测试覆盖。

因此本轮不自研分句算法，不切换识别模型，也不引入 forced alignment。

## 3. 唯一主线

采用以下处理链：

```text
录音
  -> FSMN-VAD 获取真实语音区间
  -> SenseVoice 识别中文及夹杂英文
  -> CT-Punc 恢复标点
  -> FunASR sentence_info
  -> OpenAI 兼容 text + segments
  -> Spring 现有 FunAsrClient 与状态机
  -> PostgreSQL
  -> CallRecordDetail 按标点换行显示
```

不采用以下路线：

- 不切换为 Paraformer。切换会改变现有识别模型及中英混合表现，超出“只改善文本可读性”的边界。
- 不用正则、字符数或文本长度推导句级时间。
- 不新增数据库字段，不改变电话记录状态机，不引入新的前端业务状态。
- 不维护本地 Python 与 Docker 两条并行运行主线；开发和生产均以同一 Docker Compose 服务为运行真源。

## 4. Owner 与职责边界

### 4.1 FunASR 服务

`/Users/z/FunASR/examples/openai_api/server.py` 是模型组合、推理参数、资源配置和外部响应的唯一 owner，负责：

- 为 `sensevoice` 加载 `fsmn-vad` 与 `ct-punc-c`。
- 请求句级时间输出。
- 把合法 `sentence_info` 映射成 OpenAI 兼容 `segments`。
- 保证分段起止时间来自模型结果，不用默认 `0` 补造缺失边界。
- 限制同时推理的请求数量。
- 在模型加载前配置 PyTorch 和底层数学库线程数。

### 4.2 Spring 适配层

`FunAsrClient` 继续只负责外部协议解析与领域结果规范化。此前已经确认的合同保持不变：

- 非空 `text` 是成功的最低合同。
- `segments` 可以为空。
- 非空分段必须通过数量、文本、顺序和真实音频时长校验。
- 音频时长来自上传阶段持久化的 `audioDurationSeconds`，不使用 FunASR 推理耗时。
- JDK `HttpClient` 固定使用 HTTP/1.1；当前 Uvicorn 服务不接受 h2c upgrade，默认 HTTP/2 探测会破坏 multipart 请求并返回 422。

Spring 不拥有标点算法、VAD 语义或推理线程配置。

### 4.3 前端

`CallRecordDetail` 只消费后端提供的原文和结构化分段：

- 原文保留换行并正常折行。
- 每个时间轴 item 保留一个真实起止区间。
- 分段文本内部可以按标点包含多行。
- 不重新设计抽屉、卡片、按钮或详情布局。

## 5. 时间戳与换行语义

时间戳按以下优先级生成：

1. FunASR 能把标点句与模型输出时间对齐时，每句使用对齐后的真实起止时间。
2. 无法完成句级对齐但存在可信 VAD 区间时，保留 VAD 区间；该区间中的多句文本按标点换行并共享这一真实区间。
3. 没有任何可信时间边界时，不生成该分段，不用 `0`、录音总时长或文本比例补造。

示例：

```text
[00:12.40 - 00:19.85]
您好，我想查询一下这票货。
预计什么时候到港？
```

换行只在中文或英文句末标点后生成，连续标点不得产生空行。标点文本与分段文本必须来自同一次模型结果，前端不得再次推断业务分段。

## 6. 可调资源配置

FunASR 提供以下显式配置，命令行参数与环境变量最终归一化到同一配置对象：

```text
FUNASR_INTRAOP_THREADS
FUNASR_INTEROP_THREADS
FUNASR_MAX_CONCURRENCY
```

约束：

- `INTRAOP_THREADS` 控制单次模型推理可以使用的 CPU 线程数。
- `INTEROP_THREADS` 控制算子之间的并行度，默认 `1`。
- `MAX_CONCURRENCY` 控制同时进入模型推理的请求数，默认 `1`。
- 所有值必须是有明确上界的正整数；非法值在服务启动时失败，不静默回退。
- PyTorch、OpenMP 和兼容数学库的线程配置必须在模型加载前完成。
- API 层必须用有界并发门控制推理；不能依赖 Uvicorn 当前恰好单进程来形成隐式串行。

建议 profile：

| 环境 | Intra-op | Inter-op | 并发 | OS 硬上限 |
|---|---:|---:|---:|---:|
| Mac 开发机 | 1 | 1 | 1 | 无硬保证，目标约一个核心 |
| Linux 初始生产 | 4 | 1 | 1 | `cpus=4.0` |
| Linux 高速生产 | 6-8 | 1 | 1 | `cpus=6.0-8.0` |

生产调优先增加单任务线程数，再根据真实吞吐、内存和排队长度决定是否增加并发。不得默认通过多请求并发抢满服务器。

## 7. Docker 运行边界

开发与生产服务由 Docker Compose 管理。Compose 通过 `.env` 读取资源参数，通过 `cpus` 提供硬限制，并至少包含：

- 固定的 FunASR 版本、健康检查与 `restart: unless-stopped`。
- `FUNASR_INTRAOP_THREADS`、`FUNASR_INTEROP_THREADS`、`FUNASR_MAX_CONCURRENCY` 和 `FUNASR_CPU_LIMIT`。
- named volume 持久化模型缓存，容器重建不重复下载模型。
- 镜像安装 `ffmpeg/ffprobe`，接口从媒体读取真实时长。
- 生产端口只绑定 Spring 可访问的私有网络或主机地址，不直接暴露公网。

修改资源档位只需更新 `.env` 并重建或重启服务，不修改应用源码。线程档位和 `FUNASR_CPU_LIMIT` 必须配套，避免容器限额低于模型线程需求。

## 8. 错误处理

- VAD、SenseVoice 或 CT-Punc 任一步失败时，接口返回结构化转录失败，Spring 继续使用现有重试和失败状态。
- 不把标点阶段失败后的无标点文本伪装为本轮功能已经成功。
- `sentence_info` 中缺失、非有限、倒序或负数的时间边界视为无效响应。
- 合法的 `segments=[]` 仍由 Spring 按现有兼容合同保存完整非空文本；这是无法获得可信时间轴时的降级，而不是虚构分段。
- 并发门达到上限时请求有界等待；等待时间必须受现有 HTTP 请求超时约束，不能创建无界后台任务。
- 服务启动时资源配置非法、模型加载失败或必要模型不可用，健康检查必须保持未就绪。

## 9. 测试与验收

### 9.1 FunASR 单元与协议测试

- `sensevoice` 加载 `fsmn-vad` 和 `ct-punc-c`。
- 推理请求开启句级时间输出。
- 中文、中文夹英文和连续标点按标点换行，不产生空行。
- 模型句级时间优先于 VAD 回退。
- 对齐失败时保留真实 VAD 区间，且一个区间允许包含多行。
- 缺失真实时间边界时不补 `0`。
- 非法线程数和并发数启动失败。
- 并发门不允许超过配置中的同时推理数。

### 9.2 Spring 回归

- 继续接受 `text` 非空且 `segments=[]`。
- 新的非空分段通过真实录音时长校验并保存。
- 分段超时长、倒序、文本为空或字段缺失时仍拒绝。
- Worker、重试接口和状态机现有测试全部通过。

### 9.3 前端验收

- 原文中的换行可见。
- 时间轴 item 中的多行文本不溢出、不与时间标签重叠。
- 详情仍在现有右侧 Drawer 中打开，不恢复独立页面或新窗口。
- 空分段时只显示原文，不展示虚构时间轴。

### 9.4 真实运行验收

使用至少三条真实录音：中文、中文夹英文、包含长静音。记录：

- 音频时长、推理耗时和实时倍率。
- FunASR 进程平均及峰值 CPU。
- 峰值内存。
- 分段数量、起止时间和文本。

Mac 使用 `1/1/1` profile，验收目标为长期约一个核心；由于 macOS 没有本设计提供的进程硬配额，不声称绝对不超过 `150%`。

Linux 分别使用 2、4、6 个 intra-op 线程测试，容器 `cpus` 与线程档位一致。完成标准：

- CPU 不突破容器硬上限。
- 增加线程数后真实转录耗时有可测量改善。
- 所有时间戳都来自模型或 VAD，且不超过真实录音时长。
- 中文及夹杂英文文本具备可读标点和换行。
- 目标测试、后端回归、前端构建及 `git diff --check` 全部通过。

## 10. 变更边界

本轮允许修改：

- `/Users/z/FunASR/examples/openai_api/server.py` 及其直接测试、运行文档和 systemd 模板。
- Spring FunASR adapter 的测试；只有外部响应合同确实需要映射调整时才修改生产代码。
- `CallRecordDetail` 的换行渲染及对应测试。
- 本设计文档及后续实现计划。

本轮禁止触碰：

- ChatApp、WeCom、邮件、登录和联系人模块。
- 电话记录数据库 schema、状态机语义和重试合同。
- 用户已经否定的独立详情页面或额外前端视觉设计。
- 与本轮无关的 dirty worktree 文件和现有测试失败。

本设计更新了 `2026-08-07-funasr-empty-segments-design.md` 中“本轮不修改外部 `/Users/z/FunASR`”的历史实施边界。空分段兼容合同继续有效；本轮新增工作由 FunASR owner 正式提供标点和真实时间轴。
