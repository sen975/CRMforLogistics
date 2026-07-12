# WeCom Archive App

最小企业微信官方会话内容存档工具。它只使用官方会话存档 SDK，不读取本地企业微信客户端缓存，也不做本地数据库解密。

## 功能

- 配置 CorpID、会话存档 Secret、私钥路径、官方 SDK DLL 路径。
- 手动拉取官方会话存档消息并解密入库。
- SQLite 本地存储消息、原始 JSON 和媒体下载记录。
- 按关键词、发送人、群 ID、消息类型检索。
- 对图片、语音、文件、视频、表情等消息按 `sdkfileid` 下载原始媒体文件。
- 语音只保留原文件，不做语音识别和文本转写。

## 启动

```powershell
cd D:\WorkItems\CRMforLogistics\wecom-archive-app
python -m pip install -r requirements.txt
python app.py
```

浏览器打开：

```text
http://127.0.0.1:8067
```

## 配置

可以在页面里保存配置，也可以编辑 `config.json`：

```json
{
  "wxwork_archive": {
    "corp_id": "wwxxxxxxxxxxxxxxxx",
    "secret": "会话存档Secret",
    "private_key_path": "private_key.pem",
    "sdk_lib_path": "WeWorkFinanceSdk.dll",
    "db_path": "data/wxwork_archive.db",
    "media_dir": "data/media",
    "proxy": "",
    "passwd": "",
    "timeout": 5
  }
}
```

`private_key_path` 是企业微信会话内容存档后台配置的私钥文件路径。`sdk_lib_path` 是官方会话存档 SDK 的动态库路径，Windows 下一般是 `WeWorkFinanceSdk.dll`。

## 数据流

```text
GetChatData -> 私钥解 encrypt_random_key -> DecryptData -> SQLite
                                          -> GetMediaData 下载原始媒体
```

## 目录

```text
wecom-archive-app/
  app.py                 # HTTP API 和静态页面服务
  archive_core.py        # 官方 SDK、解密、存储、检索核心
  config.json            # 本地配置
  static/                # 最小交互界面
  data/                  # SQLite 和下载媒体
  tests/                 # 核心与 API 测试
```

## 注意

- 真实同步前，需要企业微信后台已开通会话内容存档，并填写正确的 Secret、私钥和 SDK DLL。
- 页面保存配置时不会回显 Secret 和代理密码。
- 若之前在日志或截图里暴露过 Token、EncodingAESKey、Secret 或私钥，建议在企业微信后台轮换。
