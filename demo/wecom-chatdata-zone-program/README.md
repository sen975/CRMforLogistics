# 企业微信会话展示与官方摘要专区程序

该工程在同一个企业微信数据与智能专区程序中实现两个编译期固定能力：

- `conversation_viewer_sync` 调用官方 `sync_msg`，向 8107 返回会话展示组件需要的最小索引。
- `conversation_daily_summary` 调用官方 `create_summary_task` / `get_summary_result`，只返回任务状态和官方摘要。

程序不会返回或持久化消息正文，不做 Topic、客户画像或自建模型分析。

## 官方输入

构建必须使用企业微信 Java 1.4.0 官方示例源码包：

- 下载地址：`https://dldir1.qq.com/wework/wwopen/spec/demo_src/java_demo_src_1.4.0.tar.gz`
- SHA-256：`9d793d028c217f63ff55af753e5715b53d3e3321955169e4a3d798b35e33c6fb`

官方 `SpecCallbackSDK`、`SpecSDK` 和 `libWeWorkSpecSDK.so` 不在 Git 中重新分发。准备脚本校验原包后，在 `target/` 内生成构建输入。

## Viewer 能力协议

输入字段只有：

```json
{"cursor":"可选，最多128字节","limit":200,"token":"可选，最多128字节"}
```

程序固定向 SDK 发送 `mode=0`，固定调用 `sync_msg`。输出只包含：

```text
errcode, errmsg, has_more, next_cursor, msg_list[
  msgid, sender, receiver_list, chatid, send_time, msgtype,
  service_encrypt_info.encrypted_secret_key,
  service_encrypt_info.public_key_ver
]
```

未知输入、SDK 失败、畸形或超大响应统一返回 `errcode=710660`，不回显 SDK 响应或敏感数据。

## 摘要能力协议

提交：

```json
{"operation":"submit","jobid":"","msg_list":[{"msgid":"...","secret_key":"..."}]}
```

轮询：

```json
{"operation":"poll","jobid":"JOBID","msg_list":[]}
```

输出固定为：

```json
{"errcode":0,"errmsg":"ok","status":0,"jobid":"JOBID","summary":""}
```

`status=0/1/2` 分别表示未完成、完成、失败。单次最多 1000 条引用，输入和 SDK 响应各最多 1 MiB，摘要最多 64 KiB。`secret_key` 只在本次 SDK 调用内使用，不记录、不写文件、不回显。

## 构建

需要 JDK 17、Maven、Docker/BuildKit，并能够构建 `linux/amd64`：

```bash
mvn -q test
./prepare-official-sdk.sh /private/tmp/java_demo_src_1.4.0.tar.gz
./build-image.sh
```

输出：

```text
target/wecom-chatdata-zone-program-linux-amd64.tar
```

该文件按官方示例要求由 `docker export` 生成，是企业微信后台上传的 rootfs tar，不是 `docker save` 归档。程序代码只绑定 `conversation_viewer_sync` 和 `conversation_daily_summary` 两个 ability ID；该约束保存在 JAR 内，不依赖镜像环境变量。其他 ability 调用失败关闭。后台启动命令填写 `/app/start`，启动参数留空。
