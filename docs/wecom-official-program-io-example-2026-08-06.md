# 企业微信专区程序输入输出示例档案

## 官方来源

- 页面：<https://developer.work.weixin.qq.com/document/path/100051>
- 页面标题：专区程序示例
- 核对日期：2026-08-06
- 相关章节：Java -> 程序能力配置 -> 添加获取会话记录能力

## 官方 Java 示例（原样语义）

官方示例的能力 ID 为 `invoke_sync_msg`。它说明 `request_data` 内不需要再包一层 `input`，输入协议使用实际 JSON 值表示字段类型：

```json
{
  "cursor": "",
  "limit": 0,
  "token": ""
}
```

官方示例输出协议使用数字、字符串、数组和对象的实际代表值，而不是把类型说明写入字符串：

```json
{
  "errcode": 0,
  "errmsg": "ok",
  "has_more": 1,
  "next_cursor": "JGNLGEHJGIE",
  "msg_list": [
    {
      "msgid": "xxxmsgid",
      "sender": {
        "type": 1,
        "id": "woxxxxxmmgjiegjie"
      },
      "receiver_list": [
        {
          "type": 1,
          "id": "woAAAAAAAAAAA"
        },
        {
          "type": 2,
          "id": "wmXXXXXXXXXXXXX"
        }
      ],
      "chatid": "wrXXXXXXXXXX",
      "send_time": 166666666,
      "msgtype": 2,
      "service_encrypt_info": {
        "encrypted_secret_key": "KEYAAAAAAABBBBBB",
        "public_key_ver": 1
      }
    }
  ]
}
```

## 当前 `conversation_viewer_sync` 能力应填写的版本

当前镜像只接受 `cursor` 和 `limit`，不接受 `token`。因此管理后台的输入协议应填写为：

```json
{
  "cursor": "",
  "limit": 0
}
```

对应输出协议应填写为：

```json
{
  "errcode": 0,
  "errmsg": "ok",
  "has_more": 0,
  "next_cursor": "",
  "msg_list": [
    {
      "msgid": "msg_example",
      "sender": {
        "type": 1,
        "id": "wo_example_sender"
      },
      "receiver_list": [
        {
          "type": 2,
          "id": "wm_example_receiver"
        }
      ],
      "chatid": "",
      "send_time": 166666666,
      "msgtype": 1,
      "service_encrypt_info": {
        "encrypted_secret_key": "KEY_EXAMPLE",
        "public_key_ver": 1
      }
    }
  ]
}
```

## 运行时实例

8107 发给 `sync_call_program` 的外层请求中，`request_data` 是 JSON 字符串；其字符串解码后的内容为：

```json
{
  "cursor": "",
  "limit": 200
}
```

专区程序成功返回的 `response_data` 解码后为：

```json
{
  "errcode": 0,
  "errmsg": "ok",
  "has_more": 0,
  "next_cursor": "",
  "msg_list": []
}
```

## 关键结论

“输入协议/输出协议”字段应填写能表达 JSON 类型的示例对象：数字字段填数字，字符串字段填字符串，数组字段填数组。不要填写以下描述型值：

```json
{
  "cursor": "string，分页游标，首次调用传空字符串",
  "limit": "number，每页数量，必填，范围1-200"
}
```

在该写法中，`limit` 的 JSON 类型是字符串，而程序实际发送的是数字 `200`，企业微信会在程序启动前按模板拒绝请求并返回 `790016`。

## `conversation_daily_summary` 摘要能力协议

当前程序的摘要能力要求三个顶层字段始终存在。后台输入协议填写为：

```json
{
  "operation": "submit",
  "jobid": "",
  "msg_list": [
    {
      "msgid": "msg_example",
      "secret_key": "secret_example"
    }
  ]
}
```

后台输出协议填写为：

```json
{
  "errcode": 0,
  "errmsg": "ok",
  "status": 0,
  "jobid": "JOBID",
  "summary": ""
}
```

运行时提交实例：

```json
{
  "operation": "submit",
  "jobid": "",
  "msg_list": [
    {
      "msgid": "msg-001",
      "secret_key": "encrypted-secret-key"
    }
  ]
}
```

运行时轮询实例：

```json
{
  "operation": "poll",
  "jobid": "job-001",
  "msg_list": []
}
```

轮询完成时的返回实例：

```json
{
  "errcode": 0,
  "errmsg": "ok",
  "status": 1,
  "jobid": "job-001",
  "summary": "客户确认了装运时间。"
}
```
