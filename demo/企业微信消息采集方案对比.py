# -*- coding: utf-8 -*-
"""生成 企业微信消息采集方案对比.xlsx (完整版)"""
from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter
import os

wb = Workbook()

# ===== 样式 =====
hdr_fill = PatternFill(start_color="1F4E79", end_color="1F4E79", fill_type="solid")
hdr_font = Font(name="Microsoft YaHei", bold=True, color="FFFFFF", size=11)
sub_fill = PatternFill(start_color="D6E4F0", end_color="D6E4F0", fill_type="solid")
sub_font = Font(name="Microsoft YaHei", bold=True, size=10)
cell_font = Font(name="Microsoft YaHei", size=10)
bold_font = Font(name="Microsoft YaHei", bold=True, size=10)
red_font = Font(name="Microsoft YaHei", bold=True, color="FF0000", size=10)
green_font = Font(name="Microsoft YaHei", bold=True, color="008000", size=10)
title_font = Font(name="Microsoft YaHei", bold=True, size=14, color="1F4E79")
green_fill = PatternFill(start_color="E8F5E9", end_color="E8F5E9", fill_type="solid")
yellow_fill = PatternFill(start_color="FFF8E1", end_color="FFF8E1", fill_type="solid")
red_fill = PatternFill(start_color="FFEBEE", end_color="FFEBEE", fill_type="solid")
border = Border(left=Side("thin"), right=Side("thin"),
                top=Side("thin"), bottom=Side("thin"))
wrap = Alignment(wrap_text=True, vertical="center", horizontal="left")
center = Alignment(wrap_text=True, vertical="center", horizontal="center")

def sh(ws, row, n):
    for c in range(1, n+1):
        cell = ws.cell(row=row, column=c)
        cell.fill = hdr_fill; cell.font = hdr_font; cell.alignment = center; cell.border = border

def sr(ws, row, n, font=None, fill=None):
    for c in range(1, n+1):
        cell = ws.cell(row=row, column=c)
        cell.font = font or cell_font; cell.alignment = wrap; cell.border = border
        if fill: cell.fill = fill

def aw(ws, mn=12, mx=55):
    for cc in ws.columns:
        cl = get_column_letter(cc[0].column)
        ml = max((len(str(c.value or "")) for c in cc), default=0)
        ws.column_dimensions[cl].width = min(max(ml+4, mn), mx)

# ============================================================
# Sheet 1: 完整价格矩阵 (三版本 × 三渠道)
# ============================================================
ws1 = wb.active
ws1.title = "完整价格矩阵"

ws1.merge_cells("A1:H1")
ws1.cell(row=1, column=1, value="企业微信「会话内容存档」完整价格矩阵  --  2024年3月8日官方通知定价").font = title_font
ws1.cell(row=1, column=1).alignment = Alignment(horizontal="center", vertical="center")

# --- Part A: 终端客户价 (官价) ---
r = 3
ws1.merge_cells(f"A{r}:H{r}")
ws1.cell(row=r, column=1, value="A. 终端客户官方定价 (在企微管理后台直接购买)").font = Font(name="Microsoft YaHei", bold=True, size=12, color="1F4E79")

r = 4
h1 = ["版本", "单价(元/人/年)", "适用场景", "1人/年", "5人/年", "10人/年", "20人/年", "备注"]
for i, h in enumerate(h1, 1): ws1.cell(row=r, column=i, value=h)
sh(ws1, r, len(h1))

prices_c = [
    ["办公版", 200, "仅金融行业\n内部员工沟通合规", 200, 1000, 2000, 4000, "不适用物流行业"],
    ["服务版", 450, "全行业\n内外部客户沟通", 450, 2250, 4500, 9000, "不含通话录音"],
    ["企业版", 900, "全行业\n含语音/视频通话录音", 900, 4500, 9000, 18000, "你的场景需要这个"],
]
for data in prices_c:
    r += 1
    for c, v in enumerate(data, 1): ws1.cell(row=r, column=c, value=v)
    sr(ws1, r, len(h1))
    if "企业版" in str(data[0]):
        sr(ws1, r, len(h1), font=bold_font, fill=yellow_fill)

# --- Part B: 代理价 (85折) ---
r += 2
ws1.merge_cells(f"A{r}:H{r}")
ws1.cell(row=r, column=1, value="B. 代理商渠道定价 (你询问到的 85 折)").font = Font(name="Microsoft YaHei", bold=True, size=12, color="1F4E79")

r += 1
for i, h in enumerate(h1, 1): ws1.cell(row=r, column=i, value=h)
sh(ws1, r, len(h1))

prices_agent = [
    ["办公版", 340, "—", 340, 1700, 3400, 6800, "不适用"],
    ["服务版", 765, "—", 765, 3825, 7650, 15300, "—"],
    ["企业版", 1530, "—", 1530, 7650, 15300, 30600, "含通话录音"],
]
for data in prices_agent:
    r += 1
    for c, v in enumerate(data, 1): ws1.cell(row=r, column=c, value=v)
    sr(ws1, r, len(h1))
    if "企业版" in str(data[0]):
        sr(ws1, r, len(h1), font=bold_font, fill=yellow_fill)

# --- Part C: 服务商渠道价 (自己成为服务商) ---
r += 2
ws1.merge_cells(f"A{r}:H{r}")
ws1.cell(row=r, column=1, value="C. 服务商渠道定价 (自己成为服务商后购买)  ★ 推荐").font = Font(name="Microsoft YaHei", bold=True, size=12, color="008000")

r += 1
for i, h in enumerate(h1, 1): ws1.cell(row=r, column=i, value=h)
sh(ws1, r, len(h1))

prices_sp = [
    ["办公版", 100, "—", 100, 500, 1000, 2000, "不适用"],
    ["服务版", 150, "—", 150, 750, 1500, 3000, "—"],
    ["企业版", 300, "—", 300, 1500, 3000, 6000, "含通话录音 ★ 最推荐"],
]
for data in prices_sp:
    r += 1
    for c, v in enumerate(data, 1): ws1.cell(row=r, column=c, value=v)
    sr(ws1, r, len(h1), font=green_font, fill=green_fill)

# --- Part D: 三渠道横向对比 (只比企业版) ---
r += 2
ws1.merge_cells(f"A{r}:H{r}")
ws1.cell(row=r, column=1, value="D. 企业版三渠道横向对比 (你的真实需求)").font = Font(name="Microsoft YaHei", bold=True, size=12, color="1F4E79")

r += 1
h_d = ["对比项", "官方直购", "代理85折", "服务商渠道价", "官价→代理省", "官价→服务商省", "代理→服务商省", "备注"]
for i, h in enumerate(h_d, 1): ws1.cell(row=r, column=i, value=h)
sh(ws1, r, len(h_d))

comp = [
    ["单价(元/人/年)", 1800, 1530, 300, 0, 1500, 1230, "—"],
    ["企业认证(元/年)", 300, 300, 300, 0, 0, 0, "都需要"],
    ["1人首年成本", 2100, 1830, 600, 270, 1500, 1230, "含认证"],
    ["5人首年成本", 9300, 7950, 1800, 1350, 7500, 6150, "含认证"],
    ["5人第2年起", 9000, 7650, 1500, 1350, 7500, 6150, "免认证续费"],
    ["10人首年成本", 18300, 15600, 3300, 2700, 15000, 12300, "含认证"],
    ["3年5人总成本", 27300, 23250, 4800, 4050, 22500, 18450, "认证只交首年"],
]
for data in comp:
    r += 1
    for c, v in enumerate(data, 1): ws1.cell(row=r, column=c, value=v)
    is_bold = "5人" in str(data[0]) or "单价" in str(data[0])
    sr(ws1, r, len(h_d), font=bold_font if is_bold else cell_font,
       fill=None if not is_bold else yellow_fill)

# --- Summary ---
r += 2
ws1.merge_cells(f"A{r}:H{r}")
ws1.cell(row=r, column=1, value="结论: 5个销售 + 企业版 → 服务商渠道价 300/人/年 → 首年总成本 1800元 → 比官价省 7500元/年 → 比代理省 6150元/年").font = Font(name="Microsoft YaHei", bold=True, size=11, color="008000")

aw(ws1)
ws1.column_dimensions['A'].width = 22
ws1.column_dimensions['B'].width = 20

# ============================================================
# Sheet 2: 方案总览
# ============================================================
ws2 = wb.create_sheet("方案总览")

h2 = ["方案名称", "技术原理", "实时性", "消息范围", "发送消息", "风控风险", "合规性", "推荐度"]
for i, h in enumerate(h2, 1): ws2.cell(row=1, column=i, value=h)
sh(ws2, 1, len(h2))

sol = [
    ["会话存档(官方)", "官方API+SDK解密,合法合规", "准实时(5-10秒轮询)", "文字/图片/语音/文件\n+企业版含通话录音", "不能发,需走企微客户端", "无", "完全合规", "★★★★★ 推荐"],
    ["UIA控件读取", "Windows UI Automation\n读企微窗口控件树", "实时(消息到达即读)", "文字/图片/文件名\n语音仅元数据", "不能发", "无(系统级API)", "灰色(非官方)", "★★★ 备选"],
    ["内存扫描+DB解密", "扫描进程内存提取密钥\n解密本地message.db", "近实时(WAL轮询)", "全部消息类型", "不能发", "高,已验证封号", "违规", "★ 不推荐"],
    ["Hook DLL注入", "注入DLL拦截\n企微进程消息事件", "实时", "全部消息类型", "可模拟发送(风险更高)", "高,必封", "违规", "★ 不推荐"],
    ["iPad协议", "逆向移动端私有TCP协议\n伪造iPad设备登录", "实时(长连接)", "全部+通讯录+朋友圈", "可发送", "极高,设备异常检测", "严重违规,法律风险", "☆ 禁止"],
    ["KF Agent API", "企微官方客服应用API", "实时(回调推送)", "仅客服窗口消息\n不含销售私聊", "可发送", "无", "完全合规", "★★ 仅客服场景"],
]
for i, d in enumerate(sol, 2):
    for c, v in enumerate(d, 1): ws2.cell(row=i, column=c, value=v)
    sr(ws2, i, len(h2))
    if "会话存档" in str(d[0]): sr(ws2, i, len(h2), font=green_font, fill=green_fill)
    if "禁止" in str(d[-1]) or "不推荐" in str(d[-1]): sr(ws2, i, len(h2), font=red_font, fill=red_fill)

aw(ws2)

# ============================================================
# Sheet 3: 风险分析矩阵
# ============================================================
ws3 = wb.create_sheet("风险分析矩阵")

h3 = ["方案", "封号风险", "法律风险", "技术风险", "数据完整性", "长期稳定性", "综合风险等级"]
for i, h in enumerate(h3, 1): ws3.cell(row=1, column=i, value=h)
sh(ws3, 1, len(h3))

risk = [
    ["会话存档(官方)", "无", "合法合规", "官方SDK维护", "100%,所有消息类型", "永久支持,随企微升级", "零风险"],
    ["UIA控件读取", "无(系统API调用)", "灰色地带(非官方采集)", "控件树可能随版本变动", "90%,语音/特殊消息受限", "需跟随客户端更新适配", "低风险"],
    ["内存扫描+DB解密", "已确认封号\n(WXWork进程检测到异常访问)", "违反用户协议", "加密算法可能随版本变化", "100%,完整本地数据", "不可靠,已验证失败", "禁止"],
    ["Hook DLL注入", "必封\n(注入行为被安全模块检测)", "违反计算机安全法规", "每版本需重新逆向适配", "100%", "不可靠", "禁止"],
    ["iPad协议", "极高\n(伪造设备指纹,异常登录检测)", "严重违法\n破解加密协议,伪造设备身份", "每版本需重新逆向", "取决于逆向完整度", "极不稳定,随时可能被封", "严禁"],
]
for i, d in enumerate(risk, 2):
    for c, v in enumerate(d, 1): ws3.cell(row=i, column=c, value=v)
    sr(ws3, i, len(h3))
    if "零风险" in str(d[-1]): sr(ws3, i, len(h3), font=green_font, fill=green_fill)
    elif "低风险" in str(d[-1]): sr(ws3, i, len(h3), fill=yellow_fill)
    elif "禁止" in str(d[-1]) or "严禁" in str(d[-1]): sr(ws3, i, len(h3), font=red_font, fill=red_fill)

r = len(risk) + 3
ws3.merge_cells(f"A{r}:G{r}")
ws3.cell(row=r, column=1, value="重要说明").font = Font(name="Microsoft YaHei", bold=True, size=11, color="FF0000")
for note in [
    "1. 内存扫描方案已在你账号上测试,确认导致封号。请勿在正式销售账号上尝试 Hook、iPad协议等方案。",
    "2. UIA控件读取虽风险较低,但不属于官方支持的数据获取方式,不建议用于正式产品交付。",
    "3. 会话内容存档是官方唯一合法的聊天记录获取渠道。企业版300/人/年(服务商渠道价)是最优解。",
    "4. 所有非官方方案都不应作为CRM的正式消息来源。CRM核心数据模型须保持渠道中立。",
]:
    r += 1
    ws3.merge_cells(f"A{r}:G{r}")
    ws3.cell(row=r, column=1, value=note).font = Font(name="Microsoft YaHei", size=9)

aw(ws3)

# ============================================================
# Sheet 4: 服务商申请流程
# ============================================================
ws4 = wb.create_sheet("服务商申请流程")

h4 = ["阶段", "步骤", "具体操作", "耗时", "费用", "关键点"]
for i, h in enumerate(h4, 1): ws4.cell(row=1, column=i, value=h)
sh(ws4, 1, len(h4))

flow = [
    ["准备", "1. 企业营业执照", "确保有有效企业法人营业执照", "--", "--", "服务商入驻的硬性要求"],
    ["准备", "2. 企微企业认证", "登录 work.weixin.qq.com 完成企业认证", "即时~3天", "300元/年", "需超级管理员权限"],
    ["申请", "3. 提交服务商入驻", "登录 open.work.weixin.qq.com\n提交营业执照 + 产品方案", "半天", "免费", "描述: 跨境物流CRM+会话存档集成"],
    ["申请", "4. 等待审核", "企微官方审核企业资质", "7~15工作日", "免费", "期间可同步开发代码"],
    ["申请", "5. 创建代开发模板", "服务商后台-应用管理-创建代开发模板", "半小时", "免费", "不需上架应用市场\n模板审核约5分钟"],
    ["购买", "6. 购买会话存档接口", "服务商后台-应用管理-购买增值接口\n选企业版 300/人/年", "即时", "300x人数/年", "购买前可先30天免费试用"],
    ["购买", "7. 绑定企业授权", "在目标企业下授权会话存档\n设置可存档成员范围", "即时", "--", "被授权成员需知情同意"],
    ["开发", "8. 对接SDK开发", "下载官方解密SDK(C)\nPython wrapper调用\n解密消息入库CRM", "3~5天开发", "开发人力", "同步进行,不依赖审核\n可先用试用期API开发"],
]
for i, d in enumerate(flow, 2):
    for c, v in enumerate(d, 1): ws4.cell(row=i, column=c, value=v)
    sr(ws4, i, len(h4))

r = len(flow) + 3
ws4.merge_cells(f"A{r}:F{r}")
ws4.cell(row=r, column=1, value="总耗时: 2~3周  |  总前期费用: 300元(企业认证)  |  拿到企业版价格: 300元/人/年(官价1800,省1500/人/年)").font = Font(name="Microsoft YaHei", bold=True, size=11, color="1F4E79")

aw(ws4)

# ============================================================
# Sheet 5: 推荐实施路径
# ============================================================
ws5 = wb.create_sheet("推荐实施路径")

h5 = ["时间", "事项", "负责人", "产出", "备注"]
for i, h in enumerate(h5, 1): ws5.cell(row=1, column=i, value=h)
sh(ws5, 1, len(h5))

tl = [
    ["第1天", "企微后台申请会话存档30天试用", "你", "API权限开通(企业版试用)", "下载确认函-盖章-上传\n审核约3天"],
    ["第1天", "open.work.weixin.qq.com 提交服务商入驻", "你", "服务商申请提交", "同步进行,不等试用结果"],
    ["第1~3天", "Python SDK对接开发", "Claude", "消息拉取+解密+入库基础代码", "用试用期API开发\n不依赖服务商审核"],
    ["第3~7天", "CRM前端沟通时间线开发", "Claude", "Vue组件展示聊天记录\n含通话录音标记", "基于提取的chatwoot前端组件"],
    ["第2~3周", "服务商审核通过", "你", "拿到渠道价资格", "企业版300/人/年"],
    ["第3周", "购买接口 + 切换正式授权", "你", "从试用切到正式,渠道价续费", "服务商后台购买"],
    ["第4周", "全链路联调 + 上线", "一起", "销售聊天实时入CRM\n+AI摘要/跟进提醒", "正式投入使用"],
]
for i, d in enumerate(tl, 2):
    for c, v in enumerate(d, 1): ws5.cell(row=i, column=c, value=v)
    sr(ws5, i, len(h5))

aw(ws5)
ws5.column_dimensions['A'].width = 14
ws5.column_dimensions['C'].width = 12

# ===== 保存 =====
out = r"d:\WorkItems\CRMforLogistics\demo\企业微信消息采集方案对比.xlsx"
wb.save(out)
print("OK: " + out)
