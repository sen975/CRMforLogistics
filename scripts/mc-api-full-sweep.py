#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""全量前端接口测试 —— 把三张账对平：前端声明 / 后端路由 / 活体探针。

为什么是三张账
--------------
「前端接口测试」要回答的不是「单测过不过」，而是：**前端代码里声明要调的那些接口，
在真实运行的后端上到底存不存在、动词对不对、调下去会不会 5xx。** 分三层，任何一层单独看都有盲区：

  1) 前端声明面  从 `src/api/endpoints.ts`（+ `assistantStream.ts` 的 fetch/SSE）解析出 (verb, path)。
                 只读源码、不跑服务 ⇒ **写接口也能覆盖**（不能真发出去）。
  2) 后端路由表  从 `@RestController` 的 `@RequestMapping` / `@GetMapping` … 解析出 (verb, path)。
                 这是源码的真值。与声明面比对 ⇒ 抓「前端在调一个后端压根没有的接口」。
  3) 活体探针    对每条路径发 **OPTIONS**。Spring 的 `FrameworkServlet.doOptions` 只读 handler mapping、
                 **不进入任何 @RequestMapping 方法** ⇒ 零副作用；未注册路径返 404，
                 已注册返 200 且带 `Allow` 动词表。这一层专抓「源码里有、跑着的进程里没有」——
                 即 `8107 跑的还是旧 class` 那种经典症状。

判据（写死，别改口径）
----------------------
  · OPTIONS 404                        ⇔ 路径未注册（前端孤儿，或服务端陈旧）
  · OPTIONS 200 且 Allow 不含声明动词  ⇔ 动词不匹配（前端用错方法）
  · 只读接口实测 5xx                   ⇔ 真故障；4xx 是业务/参数/权限拒绝（= 路由是通的）

用法
----
    python scripts/mc-api-full-sweep.py                 # 全量：静态 + 活体（活体需 8107 在跑）
    python scripts/mc-api-full-sweep.py --no-live       # 只做静态对账
    python scripts/mc-api-full-sweep.py --base http://127.0.0.1:8107

退出码：0 = 无「必须修」的问题；1 = 有（前端孤儿 / 动词不匹配 / 活体未注册 / 只读 5xx）。
"""
from __future__ import annotations

import argparse
import http.client
import io
import json
import os
import re
import socket
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
FRONTEND = os.path.join(REPO, 'demo/message-center-spring/frontend')
BACKEND = os.path.join(REPO, 'demo/message-center-spring/backend')
OUTDIR = os.path.join(REPO, 'docs/api-tests')

ENDPOINTS_TS = os.path.join(FRONTEND, 'src/api/endpoints.ts')
STREAM_TS = os.path.join(FRONTEND, 'src/api/assistantStream.ts')
API_BASE = '/api'          # 与 src/api/session.ts 的 API_BASE 同源
SAMPLE_UUID = '11111111-1111-4111-8111-111111111111'


# ─────────────────────────────────────────────────────────────────────────────
# 一、前端声明面
# ─────────────────────────────────────────────────────────────────────────────

def _read(p):
    return io.open(p, encoding='utf-8').read()


def _skip_ws(t, i):
    while i < len(t) and t[i] in ' \t\r\n':
        i += 1
    return i


def _match_angle(t, i):
    """t[i] == '<' → 返回配平后（'>' 之后）的下标。"""
    depth, j = 0, i
    while j < len(t):
        if t[j] == '<':
            depth += 1
        elif t[j] == '>':
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    return i + 1


def _match_paren(t, i):
    """t[i] == '(' → 返回括号内的实参文本（跳过字符串里的括号）。"""
    depth, j, out, q = 0, i, [], None
    while j < len(t):
        c = t[j]
        if q:
            if c == '\\':
                out.append(c)
                j += 1
                out.append(t[j] if j < len(t) else '')
                j += 1
                continue
            if c == q:
                q = None
            out.append(c)
            j += 1
            continue
        if c in '\'"`':
            q = c
            out.append(c)
            j += 1
            continue
        if c == '(':
            depth += 1
            if depth == 1:
                j += 1
                continue
        elif c == ')':
            depth -= 1
            if depth == 0:
                return ''.join(out)
        out.append(c)
        j += 1
    return ''.join(out)


def _match_brace(t, i):
    """t[i] == '{' → 返回花括号内的文本。"""
    depth, j, out, q = 0, i, [], None
    while j < len(t):
        c = t[j]
        if q:
            if c == '\\':
                out.append(c)
                j += 1
                out.append(t[j] if j < len(t) else '')
                j += 1
                continue
            if c == q:
                q = None
            out.append(c)
            j += 1
            continue
        if c in '\'"`':
            q = c
            out.append(c)
            j += 1
            continue
        if c == '{':
            depth += 1
            if depth == 1:
                j += 1
                continue
        elif c == '}':
            depth -= 1
            if depth == 0:
                return ''.join(out)
        out.append(c)
        j += 1
    return ''.join(out)


def _split_args(txt):
    """按顶层逗号切分实参。"""
    out, depth, q, cur = [], 0, None, []
    for c in txt:
        if q:
            cur.append(c)
            if c == q:
                q = None
            continue
        if c in '\'"`':
            q = c
            cur.append(c)
            continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        if c == ',' and depth == 0:
            out.append(''.join(cur).strip())
            cur = []
            continue
        cur.append(c)
    if ''.join(cur).strip():
        out.append(''.join(cur).strip())
    return out


def _split_ternary(expr):
    """把顶层 `cond ? a : b` 拆成 [a, b]；不是三元就返回 None。"""
    depth, q, qmark = 0, None, -1
    for i, c in enumerate(expr):
        if q:
            if c == q:
                q = None
            continue
        if c in '\'"`':
            q = c
            continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif c == '?' and depth == 0:
            qmark = i
            break
    if qmark < 0:
        return None
    depth, q = 0, None
    for j in range(qmark + 1, len(expr)):
        c = expr[j]
        if q:
            if c == q:
                q = None
            continue
        if c in '\'"`':
            q = c
            continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif c == ':' and depth == 0:
            return [expr[qmark + 1:j].strip(), expr[j + 1:].strip()]
    return None


class FrontendParser:
    """把 endpoints.ts 里「声明了什么接口」抠出来。

    比正则硬的是它需要认模板串、常量拼接、`encodeURIComponent`、三元分支 —— 这些在这份
    文件里都真实出现过（`createWeComAttempt` 就是一个三元路径的函数）。
    """

    VERB = re.compile(r'client\.(get|post|put|patch|delete)\b')

    def __init__(self, src):
        self.src = src
        self.consts = {}
        for m in re.finditer(r"^const\s+([A-Za-z0-9_]+)\s*=\s*'([^']*)'\s*;", src, re.M):
            self.consts[m.group(1)] = ('lit', m.group(2))
        for m in re.finditer(r"^const\s+([A-Za-z0-9_]+)\s*=\s*\(([^)]*)\)[^={]*=>\s*(.*?);\s*$", src, re.M):
            self.consts.setdefault(m.group(1), ('fn', m.group(2), m.group(3)))
        # 本文件里的普通函数（含**未导出**的，如 whatsappManagementBase）。
        # 不认它们会把 `${fn(arg)}/xxx` 整段留成字面量 —— 26 条接口会被误报成「前端孤儿」。
        for m in re.finditer(r'^(?:export\s+)?function\s+([A-Za-z0-9_]+)\s*\(([^)]*)\)\s*(?::[^{;]*)?\{',
                             src, re.M):
            body = _match_brace(src, m.end() - 1)
            ret = re.search(r'\breturn\s+(.+?);', body, re.S)
            if ret:
                self.consts.setdefault(m.group(1), ('fn', m.group(2), ret.group(1).strip()))

    # ---- 常量展开 ----
    def _const_body(self, name):
        c = self.consts.get(name)
        if not c:
            return None
        if c[0] == 'lit':
            return c[1]
        body = c[2].strip()
        m = re.match(r'^`([^`]*)`', body, re.S) or re.match(r"^'([^']*)'", body)
        return m.group(1) if m else None

    def _expand(self, t):
        def call_sub(m):
            """`${fn(args)}` → 把 fn 的函数体拿来做参数替换。"""
            name, args = m.group(1), _split_args(m.group(2))
            c = self.consts.get(name)
            if not c or c[0] != 'fn':
                return m.group(0)
            body = c[2].strip()
            bm = re.match(r'^`([^`]*)`', body, re.S) or re.match(r"^'([^']*)'", body)
            if not bm:
                return m.group(0)
            body = bm.group(1)
            params = [p.split(':')[0].split('=')[0].strip() for p in _split_args(c[1]) if p.strip()]
            for p, a in zip(params, args):
                if p:
                    body = re.sub(r'\b%s\b' % re.escape(p), a, body)
            return body

        # 常量引用可以嵌套，逐轮替换到稳定
        for _ in range(8):
            prev = t
            t = re.sub(r'\$\{\s*([A-Za-z0-9_]+)\s*\(([^{}]*)\)\s*\}', call_sub, t)
            t = re.sub(
                r'\$\{\s*([A-Za-z0-9_]+)\s*\}',
                lambda m: (self._const_body(m.group(1)) or m.group(0)),
                t,
            )
            if prev == t:
                break
        # encoded(x) / encodeURIComponent(x) → x，其余 ${param} → {param}
        t = re.sub(r'\bencoded\(\s*([A-Za-z0-9_.\[\]]+)\s*\)', r'\1', t)
        prev = None
        while prev != t:
            prev = t
            t = re.sub(r'\$\{\s*encodeURIComponent\(\s*([A-Za-z0-9_.\[\]]+)\s*\)\s*\}', r'{\1}', t)
            t = re.sub(r'\$\{\s*([A-Za-z0-9_.\[\]]+)\s*\}', r'{\1}', t)
            t = re.sub(r"'\s*\+\s*([A-Za-z0-9_.\[\]]+)\s*\+\s*'", r'{\1}', t)
        return t

    def _resolve(self, expr):
        """返回该路径表达式对应的 1~2 条路径模板。"""
        expr = expr.strip()
        if not expr:
            return []
        tern = _split_ternary(expr)
        if tern:
            out = []
            for branch in tern:
                out.extend(self._resolve(branch))
            return out
        m = re.match(r"^'([^']*)'", expr)
        if m:
            return [m.group(1)]
        m = re.match(r'^`([^`]*)`', expr, re.S)
        if m:
            return [self._expand(m.group(1))]
        nm = re.match(r'^([A-Za-z0-9_]+)', expr)
        if nm and nm.group(1) in self.consts:
            body = self._const_body(nm.group(1))
            if body is not None:
                return [self._expand(body)]
        return []

    def functions(self):
        starts = [(m.start(), m.group(1)) for m in
                  re.finditer(r'^export\s+(?:async\s+)?function\s+([A-Za-z0-9_]+)\s*\(', self.src, re.M)]
        bounds = [(starts[i][0], starts[i][1], starts[i + 1][0] if i + 1 < len(starts) else len(self.src))
                  for i in range(len(starts))]
        return bounds

    def parse(self):
        rows, unresolved = [], []
        for (a, name, b) in self.functions():
            blk = self.src[a:b]
            hits = []
            for m in self.VERB.finditer(blk):
                i = _skip_ws(blk, m.end())
                if i < len(blk) and blk[i] == '<':
                    i = _skip_ws(blk, _match_angle(blk, i))
                if i >= len(blk) or blk[i] != '(':
                    continue
                args = _split_args(_match_paren(blk, i))
                hits.append((m.group(1).upper(), args[0] if args else ''))
            if not hits:
                unresolved.append({'fn': name, 'why': '非 client.* 调用（fetch/SSE/别名）'})
                continue
            for verb, argexpr in hits:
                paths = self._resolve(argexpr)
                if not paths:
                    unresolved.append({'fn': name, 'why': '路径表达式没还原出来: %s' % argexpr[:70]})
                    continue
                for p in paths:
                    rows.append({
                        'fn': name,
                        'method': verb,
                        'path': p,
                        'full': join_base(p),
                        'origin': 'endpoints.ts',
                    })
        return rows, unresolved


def join_base(p):
    """补上 axios baseURL 的 /api 前缀（`fetch` 那条已经在源码里自己拼好了）。"""
    if not p:
        return p
    if p.startswith(API_BASE + '/') or p == API_BASE:
        return p
    return API_BASE + ('' if p.startswith('/') else '/') + p


def parse_frontend():
    rows, unresolved = FrontendParser(_read(ENDPOINTS_TS)).parse()

    # assistantStream.ts 的 SSE 端点（走 fetch，不在 axios 实例上）
    stream = _read(STREAM_TS)
    m = re.search(r"ASSISTANT_MESSAGES_URL\s*=\s*`\$\{API_BASE\}([^`]*)`", stream)
    if m:
        rows.append({
            'fn': 'sendAssistantMessage',
            'method': 'POST',
            'path': m.group(1),
            'full': join_base(m.group(1)),
            'origin': 'assistantStream.ts',
        })
    else:
        unresolved.append({'fn': 'sendAssistantMessage', 'why': 'assistantStream.ts 里没找到 ASSISTANT_MESSAGES_URL'})

    # 别名函数（只是转调另一个端点函数）单独记一笔，供报告说明「200 个函数 ≠ 200 条接口」
    aliases = []
    for (a, name, b) in FrontendParser(_read(ENDPOINTS_TS)).functions():
        blk = _read(ENDPOINTS_TS)[a:b]
        mm = re.search(r'return\s+([a-zA-Z0-9_]+)\(', blk)
        if mm and not FrontendParser.VERB.search(blk):
            aliases.append({'fn': name, 'alias_of': mm.group(1)})
    return rows, unresolved, aliases


# ─────────────────────────────────────────────────────────────────────────────
# 二、后端路由表
# ─────────────────────────────────────────────────────────────────────────────

MAP_ANN = re.compile(
    # 全限定名也要认（`@org.springframework.web.bind.annotation.DeleteMapping(...)` 真的存在一处）
    r'@(?:[a-z][A-Za-z0-9_.]*\.)?(Get|Post|Put|Patch|Delete|Request)Mapping\s*(?:\((.*?)\))?',
    re.S,
)
JAVA_VERB = {
    'Get': 'GET', 'Post': 'POST', 'Put': 'PUT', 'Patch': 'PATCH', 'Delete': 'DELETE',
}


def _strip_comments(t):
    """去掉 // 与 /* */ 注释（跳过字符串字面量）。"""
    out, i, n, q = [], 0, len(t), None
    while i < n:
        c = t[i]
        if q:
            out.append(c)
            if c == '\\':
                if i + 1 < n:
                    out.append(t[i + 1])
                    i += 2
                    continue
            elif c == q:
                q = None
            i += 1
            continue
        if c in '"':
            q = c
            out.append(c)
            i += 1
            continue
        if c == '/' and i + 1 < n and t[i + 1] == '/':
            while i < n and t[i] != '\n':
                i += 1
            continue
        if c == '/' and i + 1 < n and t[i + 1] == '*':
            j = t.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def _ann_paths(argtxt):
    """从注解实参里取路径字面量（跳过 method = ... 那一项）。"""
    if not argtxt:
        return []
    paths = []
    for part in _split_args(argtxt):
        head = part.split('=', 1)[0].strip() if '=' in part else ''
        if head in ('method', 'consumes', 'produces', 'params', 'headers', 'name'):
            continue
        paths.extend(re.findall(r'"([^"]*)"', part))
    return paths


def _ann_methods(argtxt):
    if not argtxt:
        return []
    out = []
    for m in re.finditer(r'RequestMethod\.([A-Z]+)', argtxt):
        out.append(m.group(1))
    return out


def parse_backend():
    routes, files_scanned = [], 0
    root = os.path.join(BACKEND, 'src/main/java')
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if not f.endswith('.java'):
                continue
            p = os.path.join(dirpath, f)
            raw = _read(p)
            if '@RestController' not in raw and '@Controller' not in raw:
                continue
            files_scanned += 1
            src = _strip_comments(raw)
            cls_at = src.find('class ')
            anns = [(m.start(), m.group(1), m.group(2), m.group(0)) for m in MAP_ANN.finditer(src)]

            # 类级条件装配：被 @Conditional* / @Profile 门住的 controller，其全部路由在
            # 「条件不满足」的部署里**根本不会注册** —— 活体探针会返 404，但那不是缺陷。
            # 不认这一层，一份 404 报告会把「企微没启用」误报成 30 条接口挂掉。
            head = src[:cls_at] if cls_at > 0 else ''
            gates = re.findall(r'@(Conditional[A-Za-z]*|Profile)\b', head)
            gated = bool(gates)

            base, base_anns = '', []
            for (pos, kind, arg, _full) in anns:
                if kind == 'Request' and (cls_at < 0 or pos < cls_at):
                    base_anns.append((pos, arg))
            if base_anns:
                # 类级可能有多个（罕见），取最后一个作为 base
                base = (_ann_paths(base_anns[-1][1]) or [''])[0]

            for (pos, kind, arg, _full) in anns:
                if kind == 'Request' and (cls_at < 0 or pos < cls_at):
                    continue
                subs = _ann_paths(arg) or ['']
                verbs = [JAVA_VERB[kind]] if kind in JAVA_VERB else _ann_methods(arg)
                if not verbs:
                    continue
                line = src.count('\n', 0, pos) + 1
                for sub in subs:
                    full = _join_route(base, sub)
                    for v in verbs:
                        routes.append({
                            'verb': v,
                            'path': full,
                            'controller': f[:-5],
                            'file': os.path.relpath(p, BACKEND),
                            'line': line,
                            'base': base,
                            'sub': sub,
                            'gated': gated,
                            'gates': gates,
                        })
    # 去重（同一路径同一动词可能被两个 controller 声明 —— 那本身是要报的冲突）
    return routes, files_scanned


def _join_route(base, sub):
    if not base:
        base = ''
    if not sub:
        return base or '/'
    if not base:
        return sub
    return (base.rstrip('/') + '/' + sub.lstrip('/'))


# ─────────────────────────────────────────────────────────────────────────────
# 三、对账 + 活体探针
# ─────────────────────────────────────────────────────────────────────────────

def norm(p):
    p = p.split('?')[0]
    p = re.sub(r'\{[^}]*\}', '{}', p)
    if len(p) > 1:
        p = p.rstrip('/')
    return p or '/'


def materialize(p):
    """把 {var} 换成一个样例值（OPTIONS 不做类型转换，样例只需能匹配上模式）。"""
    def rep(m):
        inner = m.group(1)
        name, _, cons = inner.partition(':')
        if '\\d' in cons or 'digit' in cons.lower():
            return '1'
        if name.lower().endswith('id') and 'corp' in name.lower():
            return 'sample-corp'
        return SAMPLE_UUID
    return re.sub(r'\{([^}]*)\}', rep, p)


def probe_options(host, port, path, timeout=10):
    """一次 OPTIONS = 一次独立连接。

    **不能复用连接**：`getresponse()` 后若只 `read(400)`（响应体更长），残留字节会让下一条
    请求直接 `CannotSendRequest('Request-sent')` —— 一眼看去像「后端全崩了」，其实是探针的锅。
    既开新连接，也把 body 读干。
    """
    t0 = time.time()
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        conn.request('OPTIONS', path, headers={'Accept': '*/*', 'Connection': 'close'})
        r = conn.getresponse()
        body = r.read()
        allow = r.getheader('Allow') or ''
        return {
            'status': r.status,
            'allow': [x.strip().upper() for x in allow.split(',') if x.strip()],
            'ms': int((time.time() - t0) * 1000),
            'snippet': body[:160].decode('utf-8', 'replace'),
        }
    except socket.timeout:
        return {'status': 0, 'allow': [], 'ms': int((time.time() - t0) * 1000), 'error': 'TIMEOUT'}
    except Exception as e:                                            # noqa: BLE001
        return {'status': -1, 'allow': [], 'ms': int((time.time() - t0) * 1000), 'error': repr(e)[:120]}
    finally:
        conn.close()


def probe_get(host, port, path, token, timeout=20):
    t0 = time.time()
    conn = http.client.HTTPConnection(host, port, timeout=timeout)
    hdrs = {'Accept': 'application/json', 'Connection': 'close'}
    if token:
        hdrs['Authorization'] = 'Bearer ' + token
    try:
        conn.request('GET', path, headers=hdrs)
        r = conn.getresponse()
        body = r.read()
        return {
            'status': r.status,
            'ms': int((time.time() - t0) * 1000),
            'snippet': body[:240].decode('utf-8', 'replace'),
        }
    except socket.timeout:
        return {'status': 0, 'ms': int((time.time() - t0) * 1000), 'error': 'TIMEOUT'}
    except Exception as e:                                            # noqa: BLE001
        return {'status': -1, 'ms': int((time.time() - t0) * 1000), 'error': repr(e)[:120]}
    finally:
        conn.close()


def run_live(host, port, front_rows, token, progress=None):
    """活体：**每条唯一路径只发一次 OPTIONS**（OPTIONS 与动词无关），只读的再发一次 GET。"""
    by_path = {}
    for r in front_rows:
        by_path.setdefault(norm(r['full']), materialize(r['full']))

    options = {}
    for i, (n, p) in enumerate(sorted(by_path.items()), 1):
        options[n] = probe_options(host, port, p)
        if progress and i % 40 == 0:
            progress(i, len(by_path))

    results = []
    seen = set()
    for r in front_rows:
        n = norm(r['full'])
        key = (r['method'], n)
        if key in seen:
            continue
        seen.add(key)
        row = {'method': r['method'], 'norm_path': n,
               'request_path': by_path[n], 'options': options[n]}
        # 没有 token 就**别发只读**：全变 401，看着「全通过」，实际什么也没验到。
        # 宁可在报告里留白 + 显式告警，也不出一份自欺欺人的绿报告。
        if r['method'] == 'GET' and token:
            row['get'] = probe_get(host, port, by_path[n], token)
        results.append(row)
    return results


# ─────────────────────────────────────────────────────────────────────────────
# 四、报告
# ─────────────────────────────────────────────────────────────────────────────

def classify(front_rows, backend_routes, live_results):
    be_index, be_gated, be_gate_names = {}, {}, {}
    for r in backend_routes:
        n = norm(r['path'])
        be_index.setdefault(n, set()).add(r['verb'])
        be_gated[n] = be_gated.get(n, True) and bool(r.get('gated'))
        if r.get('gated'):
            for g in r.get('gates') or []:
                be_gate_names.setdefault(n, set()).add(g)
    live_index = {r['norm_path']: r for r in live_results}

    issues, ok = [], []
    for r in front_rows:
        n = norm(r['full'])
        verbs = be_index.get(n)
        live = live_index.get(n)
        rec = {
            'fn': r['fn'], 'method': r['method'], 'path': r['full'], 'norm': n,
            'in_source': bool(verbs), 'source_verbs': sorted(verbs) if verbs else [],
            'gated': bool(be_gated.get(n)), 'gates': sorted(be_gate_names.get(n, ())),
            'live_status': live['options']['status'] if live else None,
            'live_allow': live['options'].get('allow', []) if live else [],
            'get': (live or {}).get('get'),
        }
        problems = []
        if not verbs:
            problems.append('SOURCE_MISSING')
        elif r['method'] not in verbs:
            problems.append('VERB_MISMATCH')
        if live is not None:
            st = live['options']['status']
            if st == 404:
                if verbs and rec['gated']:
                    # 源码里有 + 整条路径都被条件装配门住 ⇒ 按当前配置未启用，不是缺陷
                    problems.append('LIVE_GATED')
                elif verbs:
                    # 源码里有、没被门住、进程却返 404 ⇒ 进程跑的是旧 class
                    problems.append('LIVE_STALE')
                else:
                    # 源码里也没有 ⇒ 与前端的 SOURCE_MISSING 是同一件事的两面
                    problems.append('LIVE_UNREGISTERED')
            elif st == 200 and live['options'].get('allow') and r['method'] not in live['options']['allow']:
                problems.append('LIVE_VERB_MISMATCH')
        g = rec['get']
        if g and (g['status'] >= 500 or g['status'] in (0, -1)):
            problems.append('READ_5XX')
        rec['problems'] = problems
        (issues if problems else ok).append(rec)
    return ok, issues


def render_report(meta):
    f = meta['front_rows']
    be = meta['backend_routes']
    unresolved = meta['unresolved']
    aliases = meta['aliases']
    ok, issues = meta['ok'], meta['issues']

    order = ['SOURCE_MISSING', 'VERB_MISMATCH', 'LIVE_STALE', 'LIVE_UNREGISTERED',
             'LIVE_VERB_MISMATCH', 'READ_5XX', 'LIVE_GATED']
    buckets = {k: [r for r in issues if k in r['problems']] for k in order}

    verbs = {}
    for r in f:
        verbs[r['method']] = verbs.get(r['method'], 0) + 1

    L = []
    A = L.append
    A('# 全量前端接口测试报告')
    A('')
    A('> 生成命令：`python scripts/mc-api-full-sweep.py`　·　生成时间：%s' % meta['generated_at'])
    A('> 前端源：`demo/message-center-spring/frontend/src/api/endpoints.ts`（%d 行）'
      % meta['endpoints_lines'])
    A('> 后端源：`demo/message-center-spring/backend/src/main/java`（%d 个 controller 文件）'
      % meta['backend_files'])
    A('')
    A('## 一、口径')
    A('')
    A('三层账，任何一层单独看都有盲区：')
    A('')
    A('| 层 | 怎么取 | 能抓什么 | 抓不到什么 |')
    A('|---|---|---|---|')
    A('| 前端声明面 | 解析 `endpoints.ts` 的 `client.<verb>(path)` | **写接口也能覆盖** | 服务端有没有 |')
    A('| 后端路由表 | 解析 `@RestController` 上所有 Mapping 注解 | 源码真值 | 跑着的进程是不是这份 |')
    A('| 活体 OPTIONS | 对每条路径发 `OPTIONS`，读状态码 + `Allow` | 进程陈旧 / 路径未注册 | 业务逻辑对不对 |')
    A('')
    A('**判据**：`OPTIONS 404` = 路径未注册；`Allow` 不含声明动词 = 动词用错；'
      '只读实测 `5xx` = 真故障（`4xx` 是业务拒绝，说明路由是通的）。')
    A('')
    A('「路径未注册」要再分三档 —— **三种的修法完全不同**，混在一起会把「配置没开」当成「接口坏了」：')
    A('')
    A('| 现象 | 组合 | 结论 |')
    A('|---|---|---|')
    A('| 活体 404 | 源码**有** + 类被 `@Conditional` 门住 | 按配置未启用，**不是缺陷** |')
    A('| 活体 404 | 源码**有** + 未被门禁 | **进程跑的是旧 class**（改完没重启 / 发布没跟上） |')
    A('| 活体 404 | 源码**也没有** | **前端孤儿** —— 前端在调一个后端不存在的接口 |')
    A('')
    A('## 二、总量')
    A('')
    A('| 项 | 数 |')
    A('|---|---|')
    A('| 前端声明接口（去重后 路径×动词） | **%d** |' % len({(r['method'], r['norm'] if 'norm' in r else norm(r['full'])) for r in f}))
    A('| └ GET | %d |' % verbs.get('GET', 0))
    A('| └ POST | %d |' % verbs.get('POST', 0))
    A('| └ PUT / PATCH / DELETE | %d / %d / %d |'
      % (verbs.get('PUT', 0), verbs.get('PATCH', 0), verbs.get('DELETE', 0)))
    A('| 后端路由（路径×动词） | **%d** |' % len({(r['verb'], norm(r['path'])) for r in be}))
    A('| 后端 controller 文件 | %d |' % meta['backend_files'])
    A('| 活体探针实际发出 | %d 条 OPTIONS%s |'
      % (meta['live_count'], (' + %d 条 GET' % meta['live_get']) if meta['live_get'] else ''))
    hard = [r for r in issues if any(k in r['problems'] for k in order if k != 'LIVE_GATED')]
    gated = [r for r in issues if 'LIVE_GATED' in r['problems']]
    A('| **通过** | **%d** |' % len(ok))
    A('| **有问题（需要修）** | **%d** |' % len(hard))
    A('| 按部署配置未启用（非缺陷） | %d |' % len(gated))
    A('')

    # 状态码分布：避免「通过」被读成「全是 200」—— 只读接口的 4xx 是业务拒绝，路由是通的。
    opt_dist, get_dist = {}, {}
    for r in meta['live_results']:
        s = r['options']['status']
        opt_dist[s] = opt_dist.get(s, 0) + 1
        g = r.get('get')
        if g:
            get_dist[g['status']] = get_dist.get(g['status'], 0) + 1
    A('**活体状态码分布**（`通过` 不等于 `全 200`）：')
    A('')
    A('- `OPTIONS`（%d 行）：%s' % (meta['live_count'],
                                  '、'.join('%s × %d' % (k, v) for k, v in sorted(opt_dist.items()))))
    A('- 只读实测（%d 条）：%s' % (meta['live_get'],
                                '、'.join('%s × %d' % (k, v) for k, v in sorted(get_dist.items()))))
    A('')
    A('> `OPTIONS 404` = 路径未注册（细分为「前端孤儿」与「配置未启用」）；'
      '只读 `4xx` = 业务 / 参数 / 权限拒绝（**说明路由是通的**，样本 id 是随机 UUID，查不到很正常）；'
      '只读 `5xx` = 真故障。')
    A('')
    if issues:
        A('### 问题分布')
        A('')
        A('| 类别 | 条数 | 含义 |')
        A('|---|---|---|')
        meaning = {
            'SOURCE_MISSING': '后端源码里没有这条路由 —— 前端在调一个不存在的接口',
            'VERB_MISMATCH': '路径在，但动词不对',
            'LIVE_STALE': '源码里有、**跑着的进程返 404** —— 进程跑的是旧 class',
            'LIVE_UNREGISTERED': '源码里也没有、活体也 404（与 `SOURCE_MISSING` 是同一件事的两面）',
            'LIVE_VERB_MISMATCH': '活体 Allow 里没有声明动词',
            'READ_5XX': '只读接口实测 5xx',
            'LIVE_GATED': '类上有 `@Conditional*` 门禁、当前部署未启用（**不是缺陷**）',
        }
        for k in order:
            if buckets[k]:
                A('| `%s` | %d | %s |' % (k, len(buckets[k]), meaning[k]))
        A('')
        A('> 同一条接口可能同时命中多档（例如 `/api/v1/whatsapp/templates` 既是 `VERB_MISMATCH`、'
          '也被活体的 `Allow` 判为 `LIVE_VERB_MISMATCH`）—— 所以上表各档之和会**大于**「需要修」的条数，'
          '按**接口**看以「需要修 = %d 条」为准。' % len(hard))
        A('')
        if gated:
            gate_names = sorted({g for r in gated for g in (r.get('gates') or [])})
            A('> **`LIVE_GATED` 不是缺陷。** 这批路由在后端源码里存在，但所属 controller 的类上有'
              '条件装配注解（%s），当前部署不满足条件 ⇒ **进程启动时就没注册它们**，'
              '活体探针自然 404。判据是三条同时成立：源码有 + 类被门住 + 活体 404。'
              % '、'.join('`@%s`' % g for g in gate_names))
            A('>')
            A('> 本轮命中的是**企微（WeCom）整面**：`@ConditionalOnWeComEnabled` + '
              '`@ConditionalOnExpression("not \'${app.wecom-suite-id:}\'.isBlank()")`，'
              '而 `WECOM_SUITE_ID` 为空 ⇒ 企微控制器、网关、配置全部不装配。'
              '这不是「接口坏了」，是「这套部署没开企微」。')
            A('')
    else:
        A('**没有需要修的问题。** 前端声明的每一条接口，在后端源码里都存在、动词正确，'
          '且在真实运行的进程上都被路由识别。')
        A('')

    if issues:
        A('## 三、逐个问题')
        A('')
        for k in order:
            if not buckets[k]:
                continue
            A('### `%s` · %d 条' % (k, len(buckets[k])))
            A('')
            A('| 动词 | 路径 | 前端函数 | 源码动词 | 活体 |')
            A('|---|---|---|---|---|')
            for r in buckets[k]:
                A('| %s | `%s` | `%s` | %s | %s |'
                  % (r['method'], r['path'], r['fn'],
                     ','.join(r['source_verbs']) or '—',
                     r['live_status'] if r['live_status'] is not None else '—'))
            A('')
    else:
        A('## 三、逐个问题')
        A('')
        A('无。')
        A('')

    A('## 四、通过清单（前 40 条，全量见 `sweep.json`）')
    A('')
    A('| 动词 | 路径 | 活体 | Allow | 只读实测 |')
    A('|---|---|---|---|---|')
    for r in ok[:40]:
        A('| %s | `%s` | %s | %s | %s |'
          % (r['method'], r['path'], r['live_status'],
             ','.join(r['live_allow']) or '—',
             (('%d' % r['get']['status']) if r['get'] else '—')))
    if len(ok) > 40:
        A('')
        A('…另有 %d 条通过，明细见 `sweep.json`。' % (len(ok) - 40))
    A('')

    # 后端有、前端没调
    fset = {(r['method'], norm(r['full'])) for r in f}
    orphan_backend = sorted({(r['verb'], norm(r['path'])) for r in be} - fset)
    A('## 五、后端有、前端没调（信息性，不是问题）')
    A('')
    A('共 **%d** 条。它们是 webhook、回调、或以 `curl`/第三方调用的路由。' % len(orphan_backend))
    A('')
    if orphan_backend:
        A('<details><summary>展开</summary>')
        A('')
        for v, p in orphan_backend[:120]:
            A('- `%s %s`' % (v, p))
        if len(orphan_backend) > 120:
            A('- …另有 %d 条' % (len(orphan_backend) - 120))
        A('')
        A('</details>')
        A('')

    if unresolved:
        A('## 六、没能静态解析的前端导出')
        A('')
        A('这些不是接口，或是 fetch/别名，需人工确认（不影响上面的结论）：')
        A('')
        for u in unresolved:
            A('- `%s` — %s' % (u['fn'], u['why']))
        A('')
    if aliases:
        A('## 七、别名函数（转调另一个端点函数，不重复计数）')
        A('')
        for a in aliases[:40]:
            A('- `%s` → `%s`' % (a['fn'], a['alias_of']))
        A('')

    A('## 八、怎么复现')
    A('')
    A('```bash')
    A('# 1) 静态对账（只读源码，不碰服务）')
    A('python scripts/mc-api-full-sweep.py --no-live')
    A('')
    A('# 2) 全量（需要 8107 在跑；活体 OPTIONS 不触发任何业务方法）')
    A('python scripts/mc-api-full-sweep.py')
    A('```')
    A('')
    A('活体探针**只用 OPTIONS**，不发明文 GET/POST 到写接口 —— '
      '`FrameworkServlet.doOptions` 只查 handler mapping，不会进入任何 `@RequestMapping` 方法。')
    return '\n'.join(L) + '\n'


def _esc(s):
    return (str(s).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;'))


def render_html(meta):
    """零依赖单文件 HTML（无 script，纯 HTML/CSS ⇒ 不踩「片段里混进 </script」那个坑）。"""
    f, be = meta['front_rows'], meta['backend_routes']
    ok, issues = meta['ok'], meta['issues']
    order = ['SOURCE_MISSING', 'VERB_MISMATCH', 'LIVE_STALE', 'LIVE_UNREGISTERED',
             'LIVE_VERB_MISMATCH', 'READ_5XX', 'LIVE_GATED']
    hard_keys = [k for k in order if k != 'LIVE_GATED']
    buckets = {k: [r for r in issues if k in r['problems']] for k in order}
    hard = [r for r in issues if any(k in r['problems'] for k in hard_keys)]
    gated = [r for r in issues if 'LIVE_GATED' in r['problems']]
    uniq_paths = len({norm(r['full']) for r in f})
    be_uniq = len({(r['verb'], norm(r['path'])) for r in be})
    gate_names = sorted({g for r in gated for g in (r.get('gates') or [])})

    meaning = {
        'SOURCE_MISSING': '后端源码里没有这条路由 —— 前端在调一个不存在的接口',
        'VERB_MISMATCH': '路径在，但动词不对（后端用的是别的 HTTP 方法）',
        'LIVE_STALE': '源码里有、跑着的进程返 404 —— 进程跑的是旧 class',
        'LIVE_UNREGISTERED': '源码里也没有、活体也 404',
        'LIVE_VERB_MISMATCH': '活体 Allow 头里没有声明动词',
        'READ_5XX': '只读接口实测 5xx',
        'LIVE_GATED': '类上有条件装配注解、当前部署未启用（不是缺陷）',
    }

    def rows_html(rs, cols):
        out = []
        for r in rs:
            tds = {
                'method': '<b class="m m-%s">%s</b>' % (r['method'].lower(), r['method']),
                'path': '<code>%s</code>' % _esc(r['path']),
                'fn': '<span class="fn">%s</span>' % _esc(r['fn']),
                'src': ','.join(r['source_verbs']) or '<span class="dim">—</span>',
                'live': ('<span class="dim">—</span>' if r['live_status'] is None
                         else ('<span class="bad">%s</span>' % r['live_status']
                               if r['live_status'] == 404 else str(r['live_status']))),
            }
            out.append('<tr>%s</tr>' % ''.join('<td>%s</td>' % tds[c] for c in cols))
        return '\n'.join(out)

    A = []
    A.append('<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">')
    A.append('<meta name="viewport" content="width=device-width, initial-scale=1">')
    A.append('<title>全量前端接口测试报告</title><style>')
    A.append("""
*{box-sizing:border-box}
body{margin:0;background:#f4f5f7;color:#1f2933;
 font:14px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",system-ui,sans-serif}
.wrap{max-width:1180px;margin:0 auto;padding:28px 20px 64px}
h1{font-size:24px;margin:0 0 6px}
h2{font-size:17px;margin:34px 0 12px;padding-left:10px;border-left:3px solid #2f7bf6}
.sub{color:#667085;font-size:12.5px;margin:0 0 18px}
.card{background:#fff;border:1px solid #e5e8ec;border-radius:14px;
 box-shadow:0 1px 2px rgba(16,24,40,.04),0 8px 24px rgba(16,24,40,.05);padding:18px 20px;margin:14px 0}
.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px}
.kpi{background:#fff;border:1px solid #e5e8ec;border-radius:14px;padding:16px 18px}
.kpi .n{font-size:26px;font-weight:650;letter-spacing:-.5px}
.kpi .l{font-size:12.5px;color:#667085;margin-top:2px}
.ok .n{color:#067647}.bad .n{color:#b42318}.gray .n{color:#667085}.blue .n{color:#2f7bf6}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #eef1f4;vertical-align:top}
th{font-size:12px;color:#667085;font-weight:600;background:#fafbfc;position:sticky;top:0}
tr:last-child td{border-bottom:0}
code{font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;background:#f4f6f8;
 padding:1px 5px;border-radius:5px;color:#334155;word-break:break-all}
.fn{color:#667085;font-size:12px}
.m{display:inline-block;min-width:54px;text-align:center;font-size:11.5px;padding:1px 6px;border-radius:5px}
.m-get{background:#e8f2ff;color:#1a56d6}.m-post{background:#e7f7ee;color:#067647}
.m-put{background:#fff5e5;color:#b54708}.m-patch{background:#f3ecff;color:#6941c6}
.m-delete{background:#fdeceb;color:#b42318}
.dim{color:#98a2b3}.bad{color:#b42318;font-weight:600}
.note{background:#fff8e6;border:1px solid #f5d99b;border-radius:10px;padding:12px 16px;
 font-size:13px;color:#7a4d00;margin:12px 0}
.note b{color:#5c3a00}
.fix{background:#fdeceb;border-color:#f5b5ae;color:#7a1f16}
.fix b{color:#5c1510}
details{margin:10px 0}summary{cursor:pointer;color:#2f7bf6;font-size:13px}
ul.tight{margin:8px 0 0 18px;padding:0}ul.tight li{margin:2px 0}
.pass td{color:#475467}
""")
    A.append('</style></head><body><div class="wrap">')
    A.append('<h1>全量前端接口测试报告</h1>')
    A.append('<p class="sub">生成 %s　·　活体目标 %s　·　前端源 <code>src/api/endpoints.ts</code>（%d 行）'
             '　·　后端源 <code>src/main/java</code>（%d 个 controller 文件）</p>'
             % (_esc(meta['generated_at']), _esc(meta['base']),
                meta['endpoints_lines'], meta['backend_files']))

    A.append('<div class="kpis">')
    for cls, n, l in [
        ('blue', uniq_paths, '前端声明（路径去重）'),
        ('blue', len(f), '前端声明（路径×动词）'),
        ('blue', be_uniq, '后端路由（路径×动词）'),
        ('blue', meta['live_count'], '活体 OPTIONS 实发'),
        ('ok', len(ok), '通过'),
        ('bad', len(hard), '需要修'),
        ('gray', len(gated), '按配置未启用'),
    ]:
        A.append('<div class="kpi %s"><div class="n">%s</div><div class="l">%s</div></div>' % (cls, n, l))
    A.append('</div>')

    A.append('<h2>一、口径（三层账，缺一层就有盲区）</h2>')
    A.append('<div class="card"><table><tr><th>层</th><th>怎么取</th><th>能抓什么</th><th>抓不到什么</th></tr>'
             '<tr><td>前端声明面</td><td>解析 <code>endpoints.ts</code> 的 <code>client.&lt;verb&gt;(path)</code></td>'
             '<td><b>写接口也能覆盖</b>（不需要真发出去）</td><td>服务端到底有没有</td></tr>'
             '<tr><td>后端路由表</td><td>解析 <code>@RestController</code> 上全部 Mapping 注解</td>'
             '<td>源码真值</td><td>跑着的进程是不是这份</td></tr>'
             '<tr><td>活体 OPTIONS</td><td>对每条路径发 <code>OPTIONS</code>，读状态码 + <code>Allow</code></td>'
             '<td>进程陈旧 / 路径未注册 / 动词不匹配</td><td>业务逻辑对不对</td></tr>'
             '</table>')
    A.append('<div class="note"><b>为什么用 OPTIONS 当探针：</b>Spring 的 '
             '<code>FrameworkServlet.doOptions</code> 只查 handler mapping，'
             '<b>不会进入任何 <code>@RequestMapping</code> 方法</b> —— 所以它能安全地探到每一条路径'
             '（包括写接口）而不产生任何副作用：未注册路径返 404，已注册返 200 且带 <code>Allow</code> 动词表。</div>')
    opt_dist, get_dist = {}, {}
    for r in meta['live_results']:
        s = r['options']['status']
        opt_dist[s] = opt_dist.get(s, 0) + 1
        g = r.get('get')
        if g:
            get_dist[g['status']] = get_dist.get(g['status'], 0) + 1
    A.append('<div class="note" style="background:#f7f9fb;border-color:#e5e8ec;color:#475467">'
             '<b>活体状态码分布</b>（「通过」不等于「全 200」）：<br>'
             '<code>OPTIONS</code>（%d 行）：%s<br>'
             '只读实测（%d 条）：%s<br>'
             '只读 <b>4xx</b> 是业务 / 参数 / 权限拒绝，<b>说明路由是通的</b>'
             '（样本 id 是随机 UUID，查不到很正常）；只有 <b>5xx</b> 才是真故障。</div>'
             % (meta['live_count'],
                '、'.join('%s × %d' % (k, v) for k, v in sorted(opt_dist.items())),
                meta['live_get'],
                '、'.join('%s × %d' % (k, v) for k, v in sorted(get_dist.items()))))
    A.append('</div>')

    if hard:
        A.append('<h2>二、需要修（%d 条）</h2>' % len(hard))
        A.append('<div class="note fix" style="margin-top:0">同一条接口可能同时命中多档'
                 '（例如 <code>/api/v1/whatsapp/templates</code> 既是 <code>VERB_MISMATCH</code>、'
                 '也被活体的 <code>Allow</code> 判为 <code>LIVE_VERB_MISMATCH</code>）—— '
                 '所以下面各档条数之和会大于 %d，<b>按接口看以 %d 条为准</b>。</div>'
                 % (len(hard), len(hard)))
        for k in hard_keys:
            if not buckets[k]:
                continue
            A.append('<div class="card"><b>%s</b> · %d 条　<span class="fn">%s</span>'
                     % (_esc(k), len(buckets[k]), _esc(meaning[k])))
            A.append('<table><tr><th>动词</th><th>路径</th><th>前端函数</th><th>源码动词</th><th>活体</th></tr>')
            A.append(rows_html(buckets[k], ['method', 'path', 'fn', 'src', 'live']))
            A.append('</table></div>')
    else:
        A.append('<h2>二、需要修</h2><div class="card ok-card">无。</div>')

    if gated:
        A.append('<h2>三、按部署配置未启用（%d 条，不是缺陷）</h2>' % len(gated))
        A.append('<div class="note"><b>判据是三条同时成立：</b>源码里有这条路由 + 所属 controller 的类上带条件装配注解'
                 '（%s）+ 活体探针返 404 ⇒ 进程启动时就没注册它们。<br>'
                 '本轮命中的是<b>企微整面</b>：<code>@ConditionalOnWeComEnabled</code> 与 '
                 '<code>@ConditionalOnExpression("not \'${app.wecom-suite-id:}\'.isBlank()")</code>，'
                 '而 <code>WECOM_SUITE_ID</code> 为空 ⇒ 企微的控制器 / 网关 / 配置全部不装配。'
                 '这不是「接口坏了」，是「这套部署没开企微」。</div>'
                 % '、'.join('<code>@%s</code>' % _esc(g) for g in gate_names))
        A.append('<div class="card"><table><tr><th>动词</th><th>路径</th><th>前端函数</th><th>门禁</th><th>活体</th></tr>')
        for r in gated:
            A.append('<tr><td><b class="m m-%s">%s</b></td><td><code>%s</code></td>'
                     '<td><span class="fn">%s</span></td><td><span class="fn">%s</span></td>'
                     '<td><span class="bad">404</span></td></tr>'
                     % (r['method'].lower(), r['method'], _esc(r['path']), _esc(r['fn']),
                        ','.join('@' + g for g in r['gates'])))
        A.append('</table></div>')

    A.append('<h2>四、通过清单（%d 条）</h2>' % len(ok))
    A.append('<div class="card pass"><details><summary>展开 %d 条</summary>'
             '<table><tr><th>动词</th><th>路径</th><th>前端函数</th><th>活体</th>'
             '<th>Allow</th><th>只读实测</th></tr>' % len(ok))
    for r in ok:
        last = ('<span class="dim">—</span>' if not r['get']
                else '<b%s>%s</b>' % (' class="bad"' if r['get']['status'] >= 500 else '',
                                      r['get']['status']))
        A.append('<tr><td><b class="m m-%s">%s</b></td><td><code>%s</code></td>'
                 '<td><span class="fn">%s</span></td><td>%s</td>'
                 '<td><span class="fn">%s</span></td><td>%s</td></tr>'
                 % (r['method'].lower(), r['method'], _esc(r['path']), _esc(r['fn']),
                    r['live_status'], ','.join(r['live_allow']) or '—', last))
    A.append('</table></details></div>')

    fset = {(r['method'], norm(r['full'])) for r in f}
    orphan = sorted({(r['verb'], norm(r['path'])) for r in be} - fset)
    A.append('<h2>五、后端有、前端没调（%d 条，信息性）</h2>' % len(orphan))
    A.append('<div class="card"><p class="sub" style="margin:0 0 8px">'
             'webhook、回调、或以 curl / 第三方调用的路由。</p><details><summary>展开</summary><ul class="tight">')
    for v, p in orphan[:200]:
        A.append('<li><code>%s %s</code></li>' % (v, _esc(p)))
    if len(orphan) > 200:
        A.append('<li>…另有 %d 条</li>' % (len(orphan) - 200))
    A.append('</ul></details></div>')

    if meta['unresolved']:
        A.append('<h2>六、没能静态解析的前端导出（%d 条）</h2>' % len(meta['unresolved']))
        A.append('<div class="card"><ul class="tight">')
        for u in meta['unresolved']:
            A.append('<li><code>%s</code> — %s</li>' % (_esc(u['fn']), _esc(u['why'])))
        A.append('</ul></div>')

    A.append('<h2>七、怎么复现</h2><div class="card"><pre style="margin:0;font-size:12.5px">'
             '# 静态对账（只读源码，不碰服务）\npython scripts/mc-api-full-sweep.py --no-live\n\n'
             '# 全量（需 8107 在跑；OPTIONS 不触发任何业务方法）\npython scripts/mc-api-full-sweep.py</pre></div>')
    A.append('</div></body></html>')
    return '\n'.join(A) + '\n'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', default='http://127.0.0.1:8107')
    ap.add_argument('--no-live', action='store_true')
    ap.add_argument('--token-file', default='/tmp/mc-vis-token.txt')
    ap.add_argument('--out', default=OUTDIR)
    args = ap.parse_args()

    hostport = args.base.split('//', 1)[-1].split('/')[0]
    host, _, port = hostport.partition(':')
    port = int(port or 80)

    print('== 1/4 解析前端声明面 ==')
    front_rows, unresolved, aliases = parse_frontend()
    print('   函数声明 %d 条 → 接口 %d 条；未解析 %d 条；别名 %d 条'
          % (len(front_rows) + len(unresolved), len(front_rows), len(unresolved), len(aliases)))

    print('== 2/4 解析后端路由表 ==')
    backend_routes, files_scanned = parse_backend()
    be_unique = {(r['verb'], norm(r['path'])) for r in backend_routes}
    print('   controller 文件 %d 个 → 路由 %d 条（去重 %d）'
          % (files_scanned, len(backend_routes), len(be_unique)))

    live_results = []
    if args.no_live:
        print('== 3/4 活体探针：已跳过（--no-live） ==')
    else:
        token = ''
        if os.path.exists(args.token_file):
            token = _read(args.token_file).strip()
        if not token:
            print('   ⚠️  没读到 token（%s）⇒ **跳过只读实测**（否则全返 401，是一份假绿报告）'
                  % args.token_file)
            print('      先铸登录态：python scripts/mc-cast-session.py cast <token>')
        print('== 3/4 活体探针（OPTIONS × %d 条唯一路径%s） =='
              % (len({norm(r['full']) for r in front_rows}),
                 ' + GET' if token else '；无 token ⇒ 不跑 GET'))
        live_results = run_live(
            host, port, front_rows, token,
            progress=lambda i, n: print('   … %d/%d' % (i, n)),
        )
        n404 = sum(1 for r in live_results if r['options']['status'] == 404)
        nerr = sum(1 for r in live_results if r['options']['status'] in (0, -1))
        print('   OPTIONS 完成：%d 条，404（未注册）%d 条，探针异常 %d 条'
              % (len(live_results), n404, nerr))

    print('== 4/4 对账与报告 ==')
    ok, issues = classify(front_rows, backend_routes, live_results)

    os.makedirs(args.out, exist_ok=True)
    hard = [r for r in issues if any(
        k in r['problems'] for k in
        ('SOURCE_MISSING', 'VERB_MISMATCH', 'LIVE_STALE', 'LIVE_UNREGISTERED',
         'LIVE_VERB_MISMATCH', 'READ_5XX'))]
    gated = [r for r in issues if 'LIVE_GATED' in r['problems']]
    print('   通过 %d / 需要修 %d / 按配置未启用 %d' % (len(ok), len(hard), len(gated)))
    for k in ['SOURCE_MISSING', 'VERB_MISMATCH', 'LIVE_STALE', 'LIVE_UNREGISTERED',
              'LIVE_VERB_MISMATCH', 'READ_5XX', 'LIVE_GATED']:
        n = sum(1 for r in issues if k in r['problems'])
        if n:
            print('     %-20s %d' % (k, n))
    meta = {
        'generated_at': time.strftime('%Y-%m-%d %H:%M:%S'),
        'base': args.base,
        'endpoints_lines': len(_read(ENDPOINTS_TS).split('\n')),
        'backend_files': files_scanned,
        'live_count': len(live_results),
        'live_get': sum(1 for r in live_results if r.get('get')),
        'front_rows': front_rows,
        'unresolved': unresolved,
        'aliases': aliases,
        'backend_routes': backend_routes,
        'backend_route_unique': sorted('%s %s' % (v, p) for v, p in be_unique),
        'live_results': live_results,
        'ok': ok,
        'issues': issues,
    }
    json.dump(meta, io.open(os.path.join(args.out, 'sweep.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    io.open(os.path.join(args.out, 'endpoints-frontend.json'), 'w', encoding='utf-8').write(
        json.dumps(front_rows, ensure_ascii=False, indent=1))
    io.open(os.path.join(args.out, 'routes-backend.json'), 'w', encoding='utf-8').write(
        json.dumps(backend_routes, ensure_ascii=False, indent=1))
    io.open(os.path.join(args.out, 'REPORT.md'), 'w', encoding='utf-8').write(render_report(meta))
    io.open(os.path.join(args.out, 'REPORT.html'), 'w', encoding='utf-8').write(render_html(meta))
    print('   写出 %s/{REPORT.md,REPORT.html,sweep.json,endpoints-frontend.json,routes-backend.json}' % args.out)

    # 退出码只看「需要修」—— 被配置门住的接口不是缺陷，不该让 CI 红。
    return 1 if hard else 0


if __name__ == '__main__':
    sys.exit(main())
