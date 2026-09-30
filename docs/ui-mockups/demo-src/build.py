# -*- coding: utf-8 -*-
"""把 demo-src 的碎片内联成【零依赖单文件 HTML】。

用法（在仓库根目录）：
    python3 docs/ui-mockups/demo-src/build.py

设计要点
- 每个被内联的片段都断言「不含 </script（大小写任意）」—— 否则会提前闭合脚本标签、页面静默变白。
- 接口清单来自 /tmp 的解析产物；如果没跑过解析，会明确报错而不是生成一个空目录页。
- 生成前后都打印体积与条数，方便和上一版对照。
"""
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
META = os.environ.get('MC_META', '/tmp/mc/api-meta.json')
OUT = os.path.join(ROOT, 'docs/ui-mockups/2026-09-29-workbench-v5-demo.html')

ORDER = [
    'fixtures.js',
    'app-1-core.js',
    'app-2-pages.js',
    'app-2-pages-b.js',
    'app-3-catalog.js',
]


def read(p):
    return io.open(p, encoding='utf-8').read()


def main():
    if not os.path.exists(META):
        sys.exit('缺少接口元数据 %s —— 先跑解析脚本（parse2.py + build-meta.py）' % META)
    meta = json.load(io.open(META, encoding='utf-8'))
    rows, domains = meta['rows'], meta['domains']
    assert len(rows) > 150, '接口条数异常：%d' % len(rows)
    assert len(domains) > 10, '域数量异常：%d' % len(domains)

    # 注入用的紧凑 JSON（体积优先，不换行）
    eps_js = json.dumps(rows, ensure_ascii=False, separators=(',', ':'))
    dom_js = json.dumps(domains, ensure_ascii=False, separators=(',', ':'))

    css = read(os.path.join(HERE, 'app.css'))
    parts = [read(os.path.join(HERE, f)) for f in ORDER]

    # ---- 断言：任何片段都不许含 </script ----
    for name, txt in [('app.css', css)] + list(zip(ORDER, parts)):
        if re.search(r'</\s*script', txt, re.I):
            sys.exit('%s 里出现了 </script，会提前闭合脚本标签' % name)
    for name, txt in [('endpoints', eps_js)]:
        if re.search(r'</\s*script', txt, re.I):
            sys.exit('%s 注入数据里出现了 </script' % name)

    body_js = '\n'.join(parts)

    # ---- 断言：所有字面量 call('x') 的 x 都必须在注册表里 ----
    # 调一个不存在的名字不会报错，只会在界面上显示「(未注册)」—— 静默坏画面，
    # 所以必须在构建期就拦住。（这个断言正是被 updateTodo/updateTodoApi 那次踩坑加的。）
    known = set(r['name'] for r in rows)
    called = set(re.findall(r"""\bcall\(\s*['"]([A-Za-z0-9_]+)['"]""", body_js))
    unknown = sorted(called - known)
    if unknown:
        sys.exit('以下接口名在 endpoints.ts 里不存在（会渲染成「未注册」）：%s' % ', '.join(unknown))
    print('  call() 名字核对：%d 个字面量名字，全部在注册表里' % len(called))

    html = u'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>\u7edf\u4e00\u6d88\u606f\u4e2d\u5fc3 \u00b7 \u53ef\u8fdb\u5165\u6f14\u793a\uff08v5\uff09</title>
<style>
%s
</style>
</head>
<body>
<div class="app" id="app"></div>
<noscript><p style="padding:24px;font-family:sans-serif">\u8fd9\u4e2a\u6f14\u793a\u9760\u811a\u672c\u6e32\u67d3\uff0c\u8bf7\u5f00\u542f JavaScript\u3002</p></noscript>
<script>
/* ===== \u63a5\u53e3\u6e05\u5355\uff1a\u4ece frontend/src/api/endpoints.ts \u89e3\u6790\u5f97\u51fa\uff0c\u6e90\u7801\u4e0d\u5728\u8fd9\u91cc\u624b\u5199 ===== */
window.MC_ENDPOINTS = %s;
window.MC_DOMAINS = %s;
</script>
<script>
%s
</script>
</body>
</html>
''' % (css, eps_js, dom_js, body_js)

    io.open(OUT, 'w', encoding='utf-8').write(html)
    n = len(html.encode('utf-8'))
    print('written: %s' % os.path.relpath(OUT, ROOT))
    print('  \u63a5\u53e3 %d \u6761 / \u57df %d \u4e2a / \u603b\u4f53 %.1f KB' % (len(rows), len(domains), n / 1024.0))
    print('  \u5185\u8054\u7247\u6bb5\uff1a%s' % ', '.join(ORDER))


if __name__ == '__main__':
    main()
