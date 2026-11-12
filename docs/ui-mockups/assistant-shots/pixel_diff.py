# -*- coding: utf-8 -*-
"""「展开助手不再把背景变暗」的像素级证据。

比对同一路由、同一视口下「打开面板前」与「打开面板后」两张整页截图，
取面板左侧的区域（面板从 CSS x=1102 起，截图 scale=2 ⇒ 像素 2204 以后才是面板）。
侧栏 / 消息区一个像素都不该变 —— 变了就是被盖了一层。
"""
import numpy as np
from PIL import Image

OUT = '/tmp/mc-as'
before = np.asarray(Image.open(f'{OUT}/01-打开前-整页-1512.png').convert('RGB'), dtype=np.int16)
after = np.asarray(Image.open(f'{OUT}/02-打开后-整页-1512.png').convert('RGB'), dtype=np.int16)
print('尺寸', before.shape, after.shape)
assert before.shape == after.shape

LEFT = 2100          # 面板左边界（像素）再往左留一点余量
b = before[:, :LEFT, :]
a = after[:, :LEFT, :]
d = np.abs(b - a)
changed = int((d.max(axis=2) > 0).sum())
total = b.shape[0] * b.shape[1]
print('左区（左栏 + 消息区）像素总数 =', total)
print('变化像素数 =', changed, ' 占比 = %.4f%%' % (100.0 * changed / total))
print('最大单通道差 =', int(d.max()) if changed else 0)

for x, y in [(200, 300), (600, 300), (1400, 200), (100, 1200), (2000, 800)]:
    print('  点 %-14s 打开前 %s  打开后 %s' % ((x, y), tuple(before[y, x]), tuple(after[y, x])))

print('结论：', '背景逐像素一致 —— 没有被压暗' if changed == 0 else '有像素变化，需人工核对')
