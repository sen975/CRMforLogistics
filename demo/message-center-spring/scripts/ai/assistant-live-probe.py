#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对着**活着的**服务做一次真实问答，然后把审计表读回来核验「模型这一轮到底调了什么」。

## 为什么需要它（它补的是单测补不了的那一格）

`MessageAssistantToolsTest` 能证明「入参收一队 `messageRefs` 时行为正确」，
但**证明不了模型在自然问法下真的会一次传多条** —— 那是模型行为，只有打真实供应商才看得到。
本项目里这类证据的唯一来源就是「真跑一次 + 读 `assistant_action_audit`」。

## 判据（不看模型回话，只看库里那一行）

⚠️ **先搞清楚审计表里存的是什么**：`assistant_action_audit.arguments` 存的是**参数形状**，
不是真值 —— `AssistantAuditService.argumentShape()` 把每个值换成类型名（`"string"` / `"array"` …），
真值只在 `arguments_digest`（sha256）里且不可逆。**所以数不出「这一队有几条」**，
能判定的只是**形态**：

| 库里看到 | 说明 |
|---|---|
| `arguments->>'messageRefs' = 'array'` | 模型这次用的是**一队引用**（新形态）—— 这就是本探针要的证据 |
| `arguments->>'messageRef'  = 'string'` | 旧**单条**形态（未部署新版，或模型自己退回了单条） |

顺带核对 `outcome` / `error_code` / `latency_ms` / `turn_index`，用来分辨
「这一轮坏了」与「这一轮正常但某次子调用超时」（后者见 skill `live-capability-forensics`）。

## 用法

    python3 scripts/ai/assistant-live-probe.py --token <登录后的 token> \\
        --text "把张百凡最近几条消息的原文都读一下，我要逐条引用"

    # 只读回放（不发起新问答）—— 看某个会话最近发生了什么
    python3 scripts/ai/assistant-live-probe.py --token x --conversation-id <uuid> --replay-only

## 两个必须绕的坑

- **localhost 必须绕代理**：本机 `HTTP_PROXY` 指向 127.0.0.1:xxxx，会被拦成 502。
  这里用 `ProxyHandler({})` 显式绕开，不要靠 `no_proxy` 环境变量。
- **`Authorization` 必须手写**：`/api/assistant/**` 走认证，缺 token 是 401（而不是 503）。
"""
import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.request

PG_CONTAINER_DEFAULT = "crm-logistics-message-center-postgres-1"


def open_no_proxy(req):
    # 绕开本机代理：否则 localhost 请求会被代理拦成 502，看起来像服务挂了。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    return opener.open(req)


def ask(base, token, text, conversation_id, timeout):
    """发一次真实问答，把 SSE 帧原样打印出来。返回 (http_status, frames)。"""
    payload = {"conversationId": conversation_id, "text": text, "history": []}
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base.rstrip("/") + "/api/assistant/messages", data=body,
                                method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "text/event-stream")
    req.add_header("Authorization", "Bearer " + token)
    try:
        resp = open_no_proxy(req)
    except urllib.error.HTTPError as e:
        # 「响应还没开始写」的失败仍然带真实状态码（401/503/400），要原样报出来。
        detail = e.read().decode("utf-8", "replace")[:500]
        return e.code, [f"HTTP {e.code}（未开流）: {detail}"]

    frames = []
    event = None
    for raw in resp:
        line = raw.decode("utf-8", "replace").rstrip("\n")
        if line.startswith("event:"):
            event = line[len("event:"):].strip()
        elif line.startswith("data:"):
            data = line[len("data:"):].strip()
            frames.append((event, data))
    return resp.status, frames


def audit_rows(container, conversation_id, limit):
    sql = ("select coalesce(turn_index, -1), decision, coalesce(tool_name, '-'), outcome, "
           "coalesce(error_code, '-'), coalesce(latency_ms, -1), "
           "coalesce(arguments->>'messageRefs', '-') as refs_shape, "
           "coalesce(arguments->>'messageRef', '-') as ref_shape, "
           "coalesce(arguments::text, '-'), to_char(created_at, 'HH24:MI:SS.MS') "
           "from assistant_action_audit ")
    if conversation_id:
        sql += f"where conversation_id = '{conversation_id}' "
    sql += f"order by created_at desc limit {limit}"
    out = subprocess.run(["docker", "exec", container, "psql", "-U", "message_center",
                          "-d", "message_center", "-t", "-A", "-F", "\t", "-c", sql],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit("查库失败：" + out.stderr.strip())
    rows = []
    for line in out.stdout.strip().splitlines():
        if line.strip():
            rows.append(line.split("\t"))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8107")
    ap.add_argument("--token", required=True, help="登录后的 bearer token")
    ap.add_argument("--text", help="要发给助手的那句话")
    ap.add_argument("--conversation-id", help="复用某个会话（不传则由前端/服务端决定）")
    ap.add_argument("--replay-only", action="store_true", help="不发起问答，只读回审计表")
    ap.add_argument("--timeout", type=float, default=300.0)
    ap.add_argument("--rows", type=int, default=12)
    ap.add_argument("--pg-container", default=PG_CONTAINER_DEFAULT)
    args = ap.parse_args()

    if not args.replay_only:
        if not args.text:
            raise SystemExit("--text 是必需的（或用 --replay-only）")
        status, frames = ask(args.base, args.token, args.text, args.conversation_id, args.timeout)
        print(f"HTTP {status}，{len(frames)} 帧")
        for event, data in frames:
            print(f"  event={event}  {data[:400]}")

    rows = audit_rows(args.pg_container, args.conversation_id, args.rows)
    print("\n审计（turn, decision, tool, outcome, error, latencyMs, "
          "messageRefs形状, messageRef形状, arguments, 时间）")
    used_batch = False
    used_single = False
    for r in rows:
        used_batch = used_batch or r[6] == "array"
        used_single = used_single or r[7] == "string"
        mark = ""
        if r[6] == "array":
            mark = "  <== 一队引用（新形态）"
        elif r[7] == "string":
            mark = "  <== 单条引用（旧形态）"
        print("  " + " | ".join(r[:8]) + f"  {r[8][:150]}  {r[9]}" + mark)

    if used_batch:
        verdict = "**观察到 `messageRefs` 数组形态（批量入参真的被用上了）**"
    elif used_single:
        verdict = "只观察到 `messageRef` 单条形态 ⇒ **新代码没生效**（或无 devtools 未重启）"
    else:
        verdict = ("本次窗口内没有 `message.read` 调用 —— 既不能证明也不能证伪，"
                   "换一句更明确的问法或看更长窗口（`--rows`）再试")
    print("\n结论：", verdict)
    print("（注意：审计只存参数**形状**，数不出这一队有几条；要条数得另找证据。）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
