/* ============ Demo 数据层 ============
   全部为演示用假数据，业务语料按跨境货代 / 国际物流真实场景构造。
   window.MC_ENDPOINTS / window.MC_DOMAINS 由构建脚本注入（来自真实前端源码解析）。 */

window.MC_ME = { name: '林一鸣', role: '业务经理', initials: '林', company: '深圳前海环宇国际物流' };

window.MC_CONTACTS = [
  {
    id: 'c1', displayName: '王建国', remark: '远洋货代 · 澳线大客户',
    company: '深圳市远洋国际货运代理有限公司', initials: '王', tone: 't1',
    region: '中国 · 深圳', direction: '出口', owner: '林一鸣',
    tags: ['月均 12 柜', '澳线', '账期 30 天'], aiTags: ['价格敏感', '决策快', '偏好邮件确认'],
    aiProfile: '出口至澳大利亚的 FCL 客户，年出货量约 140 柜。习惯先用邮件确认报价，再用企业微信推进订舱与单证。对目的港费用敏感，接受账期 30 天。',
    channelTypes: ['email', 'wecom', 'phone'],
    identities: [
      { id: 'i1', channelType: 'email', identityValue: 'bruce.wang@yuanyang-scm.com', displayName: '王建国', scope: 'email 账号', bindings: 3 },
      { id: 'i2', channelType: 'wecom', identityValue: 'wm3Xk9QAAAtV2nR8pLm1c', displayName: 'Bruce 王建国', scope: '企微账号', bindings: 2 },
      { id: 'i3', channelType: 'phone', identityValue: '+86 138 2655 8071', displayName: '王建国', scope: 'owner_user_id', bindings: 1 },
    ],
    lastMessageAt: '2026-09-29T10:02:00+08:00', unreadCount: 2, pinned: true,
    status: 'awaiting_me', lastText: '另外帮忙确认一下目的港派送能不能一起报。',
  },
  {
    id: 'c2', displayName: '李慧敏', remark: '中远 · 舱位对接',
    company: '广州中远物流有限公司', initials: '李', tone: 't3',
    region: '中国 · 广州', direction: '出口', owner: '林一鸣',
    tags: ['舱位资源', '欧线'], aiTags: ['流程规范', '回复慢'],
    aiProfile: '主要对接欧线舱位与船期，邮件沟通为主，回复节奏偏慢，通常在上午 10 点前处理当日邮件。',
    channelTypes: ['email', 'wecom'],
    identities: [
      { id: 'i4', channelType: 'email', identityValue: 'licm@cosco-gz.com', displayName: '李慧敏', scope: 'email 账号', bindings: 2 },
      { id: 'i5', channelType: 'wecom', identityValue: 'wm3Xk9QAAAtV2nR8pLm2d', displayName: '李慧敏', scope: '企微账号', bindings: 1 },
    ],
    lastMessageAt: '2026-09-29T09:05:00+08:00', unreadCount: 0,
    status: 'awaiting_them', lastText: '第 40 周的舱位表我这边再核一下。',
  },
  {
    id: 'c3', displayName: 'David Miller', remark: 'Oceanic Freight · 澳洲收货人',
    company: 'Oceanic Freight Pty Ltd', initials: 'DM', tone: 't2',
    region: '澳大利亚 · 悉尼', direction: '进口', owner: '林一鸣',
    tags: ['目的港派送', 'CIF'], aiTags: ['英语沟通', '要求时效'],
    aiProfile: '悉尼本地货代，负责目的港清关与派送。倾向 WhatsApp 即时沟通，对到港后的派送时效要求高。',
    channelTypes: ['email', 'chatapp'],
    identities: [
      { id: 'i6', channelType: 'email', identityValue: 'd.miller@oceanicfreight.com.au', displayName: 'David Miller', scope: 'email 账号', bindings: 1 },
      { id: 'i7', channelType: 'chatapp', identityValue: '+61 412 889 034', displayName: 'David Miller', scope: '账号 id', bindings: 1 },
    ],
    lastMessageAt: '2026-09-29T07:48:00+08:00', unreadCount: 3,
    status: 'awaiting_me', lastText: 'Can you confirm the FCL rate before Thursday? Customs broker needs it.',
  },
  {
    id: 'c4', displayName: '陈志远', remark: '鼎盛报关行',
    company: '上海鼎盛报关行', initials: '陈', tone: 't4',
    region: '中国 · 上海', direction: '出口', owner: '周敏',
    tags: ['报关', '单证'], aiTags: ['细节控'],
    aiProfile: '负责出口报关与单证复核，会逐条核对箱单与发票，喜欢电话确认后补邮件。',
    channelTypes: ['email', 'phone'],
    identities: [
      { id: 'i8', channelType: 'email', identityValue: 'chenzy@dingsheng-bg.com', displayName: '陈志远', scope: 'email 账号', bindings: 1 },
      { id: 'i9', channelType: 'phone', identityValue: '+86 139 1766 2210', displayName: '陈志远', scope: 'owner_user_id', bindings: 1 },
    ],
    lastMessageAt: '2026-09-28T17:31:00+08:00', unreadCount: 0,
    status: 'awaiting_them', lastText: '报关资料已收，明天上午放行。',
  },
  {
    id: 'c5', displayName: 'Anna Schmidt', remark: 'Nordwind · 欧线拼箱',
    company: 'Nordwind Logistik GmbH', initials: 'AS', tone: 't6',
    region: '德国 · 汉堡', direction: '进口', owner: '林一鸣',
    tags: ['LCL', '欧线'], aiTags: ['英语沟通', '单证要求高'],
    aiProfile: '汉堡本地物流商，做欧线拼箱与清关。单证要求严格，偏好 PDF 而非图片形式发送箱单。',
    channelTypes: ['email', 'chatapp'],
    identities: [
      { id: 'i10', channelType: 'email', identityValue: 'a.schmidt@nordwind-logistik.de', displayName: 'Anna Schmidt', scope: 'email 账号', bindings: 1 },
      { id: 'i11', channelType: 'chatapp', identityValue: '+49 172 553 8891', displayName: 'Anna S.', scope: '账号 id', bindings: 1 },
    ],
    lastMessageAt: '2026-09-28T20:14:00+08:00', unreadCount: 1,
    status: 'awaiting_me', lastText: 'Please send the packing list as PDF, the JPG is unreadable.',
  },
  {
    id: 'c6', displayName: '周敏', remark: '北仑码头 · 操作',
    company: '宁波北仑集装箱码头', initials: '周', tone: 't7',
    region: '中国 · 宁波', direction: '出口', owner: '周敏',
    tags: ['码头操作'], aiTags: ['即时回复'],
    aiProfile: '码头现场操作对接，只在企业微信上沟通，回复及时，多为装卸与截港时间通知。',
    channelTypes: ['wecom'],
    identities: [
      { id: 'i12', channelType: 'wecom', identityValue: 'wm3Xk9QAAAtV2nR8pLm6f', displayName: '周敏', scope: '企微账号', bindings: 1 },
    ],
    lastMessageAt: '2026-09-28T16:20:00+08:00', unreadCount: 0,
    status: 'archived', lastText: '10 月 12 日那条船截港改到 10 日上午 10 点。',
  },
  {
    id: 'c7', displayName: 'James Cooper', remark: 'Pacific Star · 美线',
    company: 'Pacific Star Trading LLC', initials: 'JC', tone: 't5',
    region: '美国 · 洛杉矶', direction: '进口', owner: '林一鸣',
    tags: ['美线', '预付'], aiTags: ['价格敏感', '多渠道路由'],
    aiProfile: '洛杉矶进口商，美线为主。同一件事会同时走邮件与 WhatsApp，需要在两个渠道间保持信息一致。',
    channelTypes: ['email', 'chatapp', 'phone'],
    identities: [
      { id: 'i13', channelType: 'email', identityValue: 'jcooper@pacificstar-trading.com', displayName: 'James Cooper', scope: 'email 账号', bindings: 2 },
      { id: 'i14', channelType: 'chatapp', identityValue: '+1 310 774 2265', displayName: 'James C.', scope: '账号 id', bindings: 1 },
      { id: 'i15', channelType: 'phone', identityValue: '+1 310 774 2219', displayName: 'Pacific Star 前台', scope: 'owner_user_id', bindings: 1 },
    ],
    lastMessageAt: '2026-09-28T22:40:00+08:00', unreadCount: 0,
    status: 'awaiting_them', lastText: 'We will confirm the booking tomorrow morning.',
  },
  {
    id: 'c8', displayName: '林晓芳', remark: '华强北电子出口',
    company: '深圳市华强北电子出口有限公司', initials: '林', tone: 't2',
    region: '中国 · 深圳', direction: '出口', owner: '李伟',
    tags: ['小批量', '空运'], aiTags: ['新客'],
    aiProfile: '新成交客户，空运小批量电子元器件，仍在建立沟通习惯。',
    channelTypes: ['wecom', 'email'],
    identities: [
      { id: 'i16', channelType: 'wecom', identityValue: 'wm3Xk9QAAAtV2nR8pLm8h', displayName: '林晓芳', scope: '企微账号', bindings: 1 },
      { id: 'i17', channelType: 'email', identityValue: 'lxf@hqb-elec.com', displayName: '林晓芳', scope: 'email 账号', bindings: 1 },
    ],
    lastMessageAt: '2026-09-28T14:12:00+08:00', unreadCount: 0,
    status: 'archived', lastText: '样品周五走空运，麻烦报个价。',
  },
];

/* 渠道显示名与色点类 */
window.MC_CH = {
  email:   { label: '邮件',     short: '邮', cls: 't-mail',   dot: 'dotmail',   color: 'var(--mail)' },
  chatapp: { label: 'WhatsApp', short: 'WA', cls: 't-chatapp', dot: 'dotwa',     color: 'var(--wa)' },
  wecom:   { label: '企业微信', short: '企', cls: 't-wecom',  dot: 'dotwecom',  color: 'var(--wecom)' },
  phone:   { label: '电话',     short: '电', cls: 't-phone',  dot: 'dotphone',  color: 'var(--phone)' },
};

/* 消息：structure 对齐 MessageResponse */
window.MC_THREADS = {
  c1: [
    { id: 'm1', dir: 'in', ch: 'email', kind: 'email', at: '2026-09-27T09:12:00+08:00', status: 'received',
      subject: '关于广东—澳大利亚运输路线与报价', from: 'bruce.wang@yuanyang-scm.com', to: 'me@huanyu-logistics.com',
      body: '林经理你好：\n\n我们有一批 3 个 40HQ 的货，深圳蛇口出，目的港悉尼，计划 10 月中出运。\n请报 FCL 全包价，并注明哪些费用不含。\n\n另外想确认一下：目的港派送如果一起走，大概什么价？\n\n王建国' },
    { id: 'm2', dir: 'out', ch: 'email', kind: 'email', at: '2026-09-27T11:40:00+08:00', status: 'read',
      subject: 'Re: 关于广东—澳大利亚运输路线与报价', from: 'me@huanyu-logistics.com', to: 'bruce.wang@yuanyang-scm.com',
      body: '王总您好：\n\n蛇口—悉尼，40HQ 报 USD 2,850/柜，含海运运费、码头操作费、文件费；不含目的港 THC、清关与派送。\n\n船期：10 月 12 日 / 10 月 19 日，航程约 18 天。舱位需在 10 月 5 日前确认。\n\n附件是正式报价单。目的港派送我这边同步问一下悉尼的代理，明天回你。\n\n林一鸣',
      atts: [{ name: '报价单-蛇口-悉尼-20260927.pdf', kind: 'document', size: '182 KB' }] },
    { id: 'm3', dir: 'in', ch: 'wecom', kind: 'text', at: '2026-09-28T08:05:00+08:00', status: 'received',
      from: 'Bruce 王建国', to: '我', body: '价格可以，先订 10 月 12 日那条船的 3 个柜。装箱单发你。' },
    { id: 'm4', dir: 'in', ch: 'wecom', kind: 'image', at: '2026-09-28T08:06:00+08:00', status: 'received',
      from: 'Bruce 王建国', to: '我', body: '', atts: [{ name: '装箱单-20260928.jpg', kind: 'image', size: '742 KB' }] },
    { id: 'm5', dir: 'out', ch: 'wecom', kind: 'text', at: '2026-09-28T08:31:00+08:00', status: 'delivered',
      from: '我', to: 'Bruce 王建国', body: '收到，正在锁舱。今天下午把 SO 编号发你，同时给到截港时间。' },
    { id: 'm6', dir: 'in', ch: 'phone', kind: 'call', at: '2026-09-28T15:52:00+08:00', status: 'completed',
      callId: 'call1', from: '王建国', to: '我', secs: 222,
      transcript: '王：船期是 12 日装是吧？\n我：对，12 日开船，10 日上午 10 点截港。\n王：那我 8 日把货送到蛇口堆场。\n我：可以，散货进仓要提前预约。另外目的港派送你确认要一起报吗？\n王：一起报，你算进去给我个总价。' },
    { id: 'm7', dir: 'out', ch: 'email', kind: 'email', at: '2026-09-29T09:20:00+08:00', status: 'sent',
      subject: '蛇口—悉尼 订舱确认 · SO 编号 SZXSYD2610012', from: 'me@huanyu-logistics.com', to: 'bruce.wang@yuanyang-scm.com',
      body: '王总您好：\n\n3×40HQ 已锁定 10 月 12 日船期，SO 编号 SZXSYD2610012。\n\n截港时间：10 月 10 日 10:00（蛇口 CCT 二期）\n提单确认截止：10 月 13 日 17:00\n\n附件是订舱确认书与提单草稿，请核对收货人与唛头。\n\n林一鸣',
      atts: [{ name: '订舱确认书-SZXSYD2610012.pdf', kind: 'document', size: '96 KB' }, { name: '提单草稿-SZXSYD2610012.pdf', kind: 'document', size: '124 KB' }] },
    { id: 'm8', dir: 'in', ch: 'email', kind: 'email', at: '2026-09-29T10:02:00+08:00', status: 'received',
      subject: 'Re: 蛇口—悉尼 订舱确认 · SO 编号 SZXSYD2610012', from: 'bruce.wang@yuanyang-scm.com', to: 'me@huanyu-logistics.com',
      body: '收到，唛头没问题。报关资料我下午发你。\n另外帮忙确认一下目的港派送能不能一起报，客户那边在催。' },
  ],
  c2: [
    { id: 'm20', dir: 'out', ch: 'email', kind: 'email', at: '2026-09-28T16:20:00+08:00', status: 'read',
      subject: '第 40 周 欧线舱位申请（汉堡 / 鹿特丹）', from: 'me@huanyu-logistics.com', to: 'licm@cosco-gz.com',
      body: '李经理你好：\n\n第 40 周需要申请的舱位：\n· 汉堡 2×40HQ\n· 鹿特丹 1×40HQ + 4×20GP\n\n麻烦看下能不能给到周中船期。谢谢。\n\n林一鸣' },
    { id: 'm21', dir: 'in', ch: 'email', kind: 'email', at: '2026-09-29T09:05:00+08:00', status: 'received',
      subject: 'Re: 第 40 周 欧线舱位申请（汉堡 / 鹿特丹）', from: 'licm@cosco-gz.com', to: 'me@huanyu-logistics.com',
      body: '林经理：\n\n第 40 周的舱位表我这边再核一下，汉堡那两柜问题不大，鹿特丹的 20GP 要等放舱。\n今天下午给你准信。\n\n李慧敏' },
  ],
  c3: [
    { id: 'm30', dir: 'out', ch: 'chatapp', kind: 'text', at: '2026-09-28T18:12:00+08:00', status: 'read',
      from: '我', to: 'David Miller', body: 'Hi David, the 3x40HQ are booked on the Oct 12 sailing from Shekou. ETA Sydney Nov 1.' },
    { id: 'm31', dir: 'in', ch: 'chatapp', kind: 'text', at: '2026-09-28T18:40:00+08:00', status: 'received',
      from: 'David Miller', to: '我', body: 'Good. We will need the arrival notice 3 days before ETA so we can pre-lodge the customs entry.' },
    { id: 'm32', dir: 'in', ch: 'chatapp', kind: 'text', at: '2026-09-29T07:48:00+08:00', status: 'received',
      from: 'David Miller', to: '我', body: 'Can you confirm the FCL rate before Thursday? Customs broker needs it.' },
  ],
  c5: [
    { id: 'm50', dir: 'out', ch: 'email', kind: 'email', at: '2026-09-28T17:02:00+08:00', status: 'read',
      subject: 'Packing list for LCL shipment HAM-2610-114', from: 'me@huanyu-logistics.com', to: 'a.schmidt@nordwind-logistik.de',
      body: 'Hi Anna,\n\nPlease find the packing list for the LCL shipment to Hamburg (ref HAM-2610-114).\n\nTotal 12 cartons, 348 kg, 2.1 cbm.\n\nBest regards,\nLin Yiming',
      atts: [{ name: 'packing-list-HAM-2610-114.jpg', kind: 'image', size: '1.2 MB' }] },
    { id: 'm51', dir: 'in', ch: 'email', kind: 'email', at: '2026-09-28T20:14:00+08:00', status: 'received',
      subject: 'Re: Packing list for LCL shipment HAM-2610-114', from: 'a.schmidt@nordwind-logistik.de', to: 'me@huanyu-logistics.com',
      body: 'Hi Lin,\n\nPlease send the packing list as PDF, the JPG is unreadable — we cannot import the weights into our system.\n\nAlso, one carton shows 61 kg, is that correct?\n\nAnna' },
  ],
  c7: [
    { id: 'm70', dir: 'in', ch: 'email', kind: 'email', at: '2026-09-28T21:05:00+08:00', status: 'received',
      subject: 'Q4 volume forecast — Pacific Star', from: 'jcooper@pacificstar-trading.com', to: 'me@huanyu-logistics.com',
      body: 'Hi Lin,\n\nAttached is our Q4 forecast. Roughly 8x40HQ per month from Yantian to LA.\nLet us know if you can hold the current rate through December.\n\nJames' },
    { id: 'm71', dir: 'out', ch: 'chatapp', kind: 'text', at: '2026-09-28T21:30:00+08:00', status: 'delivered',
      from: '我', to: 'James C.', body: 'Got the forecast, thanks. Let me check with the carrier on a Q4 rate lock and revert tomorrow.' },
    { id: 'm72', dir: 'in', ch: 'chatapp', kind: 'text', at: '2026-09-28T22:40:00+08:00', status: 'received',
      from: 'James C.', to: '我', body: 'We will confirm the booking tomorrow morning. Also sent the same to your email.' },
  ],
  c4: [
    { id: 'm40', dir: 'out', ch: 'email', kind: 'email', at: '2026-09-28T15:10:00+08:00', status: 'read',
      subject: '出口报关资料 · 远洋 SZXSYD2610012', from: 'me@huanyu-logistics.com', to: 'chenzy@dingsheng-bg.com',
      body: '陈师傅：\n\n附上 3 个 40HQ 的报关资料：报关单、发票、装箱单、合同。\n10 月 10 日截港，麻烦提前安排。\n\n林一鸣',
      atts: [{ name: '报关资料-SZXSYD2610012.zip', kind: 'document', size: '2.4 MB' }] },
    { id: 'm41', dir: 'in', ch: 'phone', kind: 'call', at: '2026-09-28T16:48:00+08:00', status: 'completed',
      callId: 'call3', from: '陈志远', to: '我', secs: 96,
      transcript: '陈：发票上的成交方式写 CIF 还是 FOB？\n我：CIF，运费已经加进去了。\n陈：好，那报关金额我按发票总额报。明天上午放行。' },
    { id: 'm42', dir: 'in', ch: 'email', kind: 'email', at: '2026-09-28T17:31:00+08:00', status: 'received',
      subject: 'Re: 出口报关资料 · 远洋 SZXSYD2610012', from: 'chenzy@dingsheng-bg.com', to: 'me@huanyu-logistics.com',
      body: '林经理：\n\n报关资料已收，明天上午放行。\n有一处要注意：箱单第 3 页的件数写的是 120，发票上是 118，请核实后回我一句。\n\n陈志远' },
  ],
  c6: [
    { id: 'm60', dir: 'in', ch: 'wecom', kind: 'text', at: '2026-09-28T16:20:00+08:00', status: 'received',
      from: '周敏', to: '我', body: '10 月 12 日那条船截港改到 10 日上午 10 点。请转告客户。' },
  ],
  c8: [
    { id: 'm80', dir: 'in', ch: 'wecom', kind: 'text', at: '2026-09-28T14:12:00+08:00', status: 'received',
      from: '林晓芳', to: '我', body: '样品周五走空运，麻烦报个价。大概 38 公斤，两箱。' },
  ],
};

/* 通话记录（独立于消息，挂 contactId） */
window.MC_CALLS = [
  { id: 'call1', contactId: 'c1', dir: 'in', at: '2026-09-28T15:52:00+08:00', secs: 222, state: 'completed',
    phone: '+86 138 2655 8071', note: '确认截港时间与目的港派送口径',
    transcript: '王：船期是 12 日装是吧？\n我：对，12 日开船，10 日上午 10 点截港。\n王：那我 8 日把货送到蛇口堆场。\n我：可以，散货进仓要提前预约。另外目的港派送你确认要一起报吗？\n王：一起报，你算进去给我个总价。' },
  { id: 'call2', contactId: 'c1', dir: 'out', at: '2026-09-27T14:20:00+08:00', secs: 64, state: 'completed',
    phone: '+86 138 2655 8071', note: '首次报价沟通', transcript: '我：报价单发你邮箱了，含海运和码头费。\n王：好，我先看下再回你。' },
  { id: 'call3', contactId: 'c4', dir: 'in', at: '2026-09-28T16:48:00+08:00', secs: 96, state: 'completed',
    phone: '+86 139 1766 2210', note: '报关成交方式确认',
    transcript: '陈：发票上的成交方式写 CIF 还是 FOB？\n我：CIF，运费已经加进去了。\n陈：好，那报关金额我按发票总额报。明天上午放行。' },
  { id: 'call4', contactId: 'c7', dir: 'out', at: '2026-09-28T23:10:00+08:00', secs: 178, state: 'processing',
    phone: '+1 310 774 2219', note: '', transcript: '（转录中…）' },
];

/* AI 话题（topic） */
window.MC_TOPICS = {
  c1: [
    { id: 't1', name: '蛇口—悉尼 FCL 报价', desc: '3×40HQ，USD 2,850/柜，含码头操作费与文件费，不含目的港费用。', at: '09-27', conf: 0.94, state: 'active', src: 4 },
    { id: 't2', name: '订舱与 SO 编号', desc: '已锁 10 月 12 日船期，SO=SZXSYD2610012，截港 10 月 10 日 10:00。', at: '09-29', conf: 0.91, state: 'active', src: 3 },
    { id: 't3', name: '目的港派送报价', desc: '客户明确要求把悉尼派送一起报进总价，尚未给出口径。', at: '09-29', conf: 0.88, state: 'pending', src: 2 },
    { id: 't4', name: '报关资料', desc: '客户称下午提供；报关行已就件数不一致提出核对。', at: '09-29', conf: 0.83, state: 'pending', src: 3 },
    { id: 't5', name: '提单确认', desc: '提单草稿已发，唛头客户已确认无异议。', at: '09-29', conf: 0.79, state: 'active', src: 2 },
  ],
  c3: [
    { id: 't6', name: '悉尼到港前预申报', desc: 'David 要求 ETA 前 3 天发到货通知，用于提前报关。', at: '09-28', conf: 0.9, state: 'active', src: 2 },
    { id: 't7', name: 'FCL 费率确认', desc: '报关行需要费率，David 要求周四前给出。', at: '09-29', conf: 0.86, state: 'pending', src: 1 },
  ],
  c5: [
    { id: 't8', name: '拼箱箱单重发', desc: 'Anna 要求改发 PDF 并核对第 3 箱 61 kg 是否属实。', at: '09-28', conf: 0.89, state: 'pending', src: 2 },
  ],
  c7: [
    { id: 't9', name: 'Q4 运价锁定', desc: 'James 要求盐田—洛杉矶维持现价至 12 月，月均 8×40HQ。', at: '09-28', conf: 0.85, state: 'pending', src: 2 },
  ],
};

/* 待办 */
window.MC_TODOS = [
  { id: 'td1', date: '2026-09-29', time: '09:30', title: '回复 Oceanic Freight 的 FCL 费率（周四前）', done: false, hot: true, from: 'c3' },
  { id: 'td2', date: '2026-09-29', time: '11:00', title: '把 Nordwind 的箱单改发 PDF 并核对 61 kg', done: false, hot: true, from: 'c5' },
  { id: 'td3', date: '2026-09-29', time: '14:00', title: '给王总回悉尼目的港派送总价', done: false, from: 'c1' },
  { id: 'td4', date: '2026-09-29', time: '15:30', title: '核对报关装箱单件数 120 / 118 不一致', done: true, from: 'c4' },
  { id: 'td5', date: '2026-09-29', time: '16:30', title: '跟 Pacific Star 确认 Q4 运价锁定结果', done: false, from: 'c7' },
  { id: 'td6', date: '2026-09-30', time: '10:00', title: '催 第 40 周鹿特丹 20GP 放舱', done: false, from: 'c2' },
  { id: 'td7', date: '2026-10-05', time: '17:00', title: '确认 10/12 船期舱位最终名单', done: false, from: 'c1' },
  { id: 'td8', date: '2026-10-10', time: '10:00', title: '截港：SZXSYD2610012（蛇口 CCT 二期）', done: false, hot: true, from: 'c1' },
];

/* 渠道账号（渠道设置） */
window.MC_ACCOUNTS = [
  { id: 'a1', channel: 'email', name: '业务主邮箱', ident: 'me@huanyu-logistics.com', status: 'active', synced: '2026-09-29T10:12:00+08:00', creds: 'IMAP + SMTP' },
  { id: 'a2', channel: 'email', name: '客服邮箱', ident: 'service@huanyu-logistics.com', status: 'active', synced: '2026-09-29T10:11:00+08:00', creds: 'IMAP + SMTP' },
  { id: 'a3', channel: 'wecom', name: '前海环宇 · 企微自建应用', ident: 'ww9f2c1d8e6a4b (corpId)', status: 'active', synced: '2026-09-29T09:58:00+08:00', creds: 'Secret + 通讯录同步' },
  { id: 'a4', channel: 'chatapp', name: 'Cloud API 主号', ident: '+86 755 8899 0120', status: 'active', synced: '2026-09-29T10:05:00+08:00', creds: 'WABA + CAMS' },
  { id: 'a5', channel: 'phone', name: '座机录音网关', ident: '+86 755 8899 0100', status: 'warning', synced: '2026-09-28T22:40:00+08:00', creds: 'SIP 中继', note: '昨晚 4 条录音转录失败' },
];

/* 企微群 */
window.MC_WECOM_GROUPS = [
  { id: 'g1', name: '澳洲线客户群 · 远洋', members: 9, owner: '林一鸣', at: '2026-09-29T09:44:00+08:00', unread: 4,
    text: '【系统】群名刷新完成；新增外部联系人 1 位',
    topics: ['10/12 船期装柜安排', '目的港派送报价', '提单确认'] },
  { id: 'g2', name: '宁波—欧洲线操作群', members: 14, owner: '周敏', at: '2026-09-28T17:02:00+08:00', unread: 0,
    text: '第 40 周舱位表已更新', topics: ['舱位放舱', '截港时间变更'] },
  { id: 'g3', name: '美线 Q4 预估对接', members: 6, owner: '李伟', at: '2026-09-28T22:41:00+08:00', unread: 1,
    text: 'James：Q4 预估已发邮件，请同步', topics: ['Q4 运价锁定'] },
];

/* 消息模板 */
window.MC_TEMPLATES = [
  { id: 'tp1', name: '报价通知（澳线 FCL）', ch: 'email', cat: 'MARKETING', lang: 'zh_CN', state: 'approved', used: 42,
    body: '{{客户称呼}}您好：\n\n{{起运港}}—{{目的港}}，{{箱型}} 报 {{价格}}，含 {{包含费用}}；不含 {{不含费用}}。\n船期 {{船期}}，舱位需在 {{截止日}} 前确认。' },
  { id: 'tp2', name: '到港提醒（ETA-3）', ch: 'chatapp', cat: 'UTILITY', lang: 'en', state: 'approved', used: 118,
    body: 'Hi {{1}}, this is a reminder that shipment {{2}} is expected to arrive at {{3}} on {{4}}. Please prepare the customs documents.' },
  { id: 'tp3', name: '文件索取（箱单 / 发票）', ch: 'chatapp', cat: 'UTILITY', lang: 'en', state: 'pending', used: 27,
    body: 'Hi {{1}}, could you send the packing list and commercial invoice for shipment {{2}}? A PDF is preferred.' },
  { id: 'tp4', name: '拼箱装箱单确认', ch: 'email', cat: 'UTILITY', lang: 'zh_CN', state: 'approved', used: 15,
    body: '{{客户称呼}}：\n\n附件为 {{提单号}} 的装箱单，共 {{件数}} 件 / {{重量}} / {{体积}}，请核对后回复确认。' },
  { id: 'tp5', name: '账单与对账提醒', ch: 'email', cat: 'UTILITY', lang: 'zh_CN', state: 'rejected', used: 0,
    body: '{{客户称呼}}：本月对账单已生成，账期 {{账期}}，请于 {{到期日}} 前完成付款。' },
  { id: 'tp6', name: 'Q4 运价锁定通知', ch: 'chatapp', cat: 'MARKETING', lang: 'en', state: 'in_review', used: 0,
    body: 'Hi {{1}}, we can hold your current rate through {{2}} for bookings made before {{3}}.' },
];

/* 群发任务 */
window.MC_BROADCASTS = [
  { id: 'b1', name: '9 月澳线到港提醒', ch: 'chatapp', at: '2026-09-28T09:00:00+08:00', state: 'partial',
    total: 186, sent: 179, failed: 5, pending: 2, tpl: '到港提醒（ETA-3）',
    failures: [
      { to: '+61 412 889 034', reason: 'recipient_not_opted_in', at: '09:02' },
      { to: '+61 400 221 776', reason: 'invalid_number', at: '09:02' },
      { to: '+61 433 908 112', reason: 'rate_limited', at: '09:03' },
      { to: '+61 421 556 003', reason: 'rate_limited', at: '09:03' },
      { to: '+61 455 771 220', reason: 'template_param_missing', at: '09:04' },
    ] },
  { id: 'b2', name: 'Q4 运价锁定预告（美线）', ch: 'chatapp', at: '2026-09-29T08:30:00+08:00', state: 'sending',
    total: 64, sent: 41, failed: 0, pending: 23, tpl: 'Q4 运价锁定通知', failures: [] },
  { id: 'b3', name: '拼箱箱单确认（欧线）', ch: 'email', at: '2026-09-27T14:00:00+08:00', state: 'completed',
    total: 38, sent: 38, failed: 0, pending: 0, tpl: '拼箱装箱单确认', failures: [] },
];

/* 电话仓库 */
window.MC_PHONE_REPO = [
  { id: 'p1', num: '+86 138 2655 8071', label: '王建国 · 远洋货代', bind: '王建国（c1）', calls: 14, last: '2026-09-28 15:52' },
  { id: 'p2', num: '+86 139 1766 2210', label: '陈志远 · 鼎盛报关', bind: '陈志远（c4）', calls: 9, last: '2026-09-28 16:48' },
  { id: 'p3', num: '+61 412 889 034', label: 'David Miller · 悉尼', bind: 'David Miller（c3）', calls: 3, last: '2026-09-28 12:10' },
  { id: 'p4', num: '+1 310 774 2219', label: 'Pacific Star 前台', bind: 'James Cooper（c7）', calls: 2, last: '2026-09-28 23:10' },
  { id: 'p5', num: '+86 755 8899 0100', label: '座机主号（录音网关）', bind: '—', calls: 126, last: '2026-09-29 09:31' },
];

/* 渠道通讯录 */
window.MC_ADDRBOOK = {
  email: [
    { id: 'ab1', name: '王建国', addr: 'bruce.wang@yuanyang-scm.com', org: '深圳市远洋国际货运代理有限公司', matched: '王建国（c1）', src: '联系人来信' },
    { id: 'ab2', name: '李慧敏', addr: 'licm@cosco-gz.com', org: '广州中远物流有限公司', matched: '李慧敏（c2）', src: '联系人来信' },
    { id: 'ab3', name: 'Anna Schmidt', addr: 'a.schmidt@nordwind-logistik.de', org: 'Nordwind Logistik GmbH', matched: 'Anna Schmidt（c5）', src: '联系人来信' },
    { id: 'ab4', name: '未匹配', addr: 'ops@szport-terminal.com', org: '—', matched: '—', src: '手动新增' },
  ],
  wecom: [
    { id: 'ab5', name: 'Bruce 王建国', addr: 'wm3Xk9QAAAtV2nR8pLm1c', org: '深圳市远洋国际货运代理有限公司', matched: '王建国（c1）', src: '企微外部联系人' },
    { id: 'ab6', name: '周敏', addr: 'wm3Xk9QAAAtV2nR8pLm6f', org: '宁波北仑集装箱码头', matched: '周敏（c6）', src: '企微外部联系人' },
    { id: 'ab7', name: '李明（未加好友）', addr: 'wm3Xk9QAAAtV2nR8pLm9z', org: '—', matched: '—', src: '手动新增' },
  ],
  chatapp: [
    { id: 'ab8', name: 'David Miller', addr: '+61 412 889 034', org: 'Oceanic Freight Pty Ltd', matched: 'David Miller（c3）', src: 'WhatsApp 入站' },
    { id: 'ab9', name: 'James C.', addr: '+1 310 774 2265', org: 'Pacific Star Trading LLC', matched: 'James Cooper（c7）', src: 'WhatsApp 入站' },
    { id: 'ab10', name: 'Anna S.', addr: '+49 172 553 8891', org: 'Nordwind Logistik GmbH', matched: 'Anna Schmidt（c5）', src: 'WhatsApp 入站' },
  ],
  phone: [
    { id: 'ab11', name: '王建国', addr: '+86 138 2655 8071', org: '深圳市远洋国际货运代理有限公司', matched: '王建国（c1）', src: '通话记录' },
    { id: 'ab12', name: '陈志远', addr: '+86 139 1766 2210', org: '上海鼎盛报关行', matched: '陈志远（c4）', src: '通话记录' },
    { id: 'ab13', name: '未知号码', addr: '+86 186 0021 4477', org: '—', matched: '—', src: '通话记录' },
  ],
};

/* 联系人记忆（AI 侧） */
window.MC_MEMORY = {
  c1: {
    updatedAt: '2026-09-29T10:05:00+08:00', state: 'fresh', turns: 6, lastInbound: '2026-09-29T10:02:00+08:00',
    summary: '王建国是澳线大客户，出货节奏稳定（月均 12 柜）。沟通习惯：报价用邮件确认，执行用企业微信推进。目前有 3×40HQ 在 10/12 船期上，已锁舱并确认唛头；待办是目的港派送报价与报关资料核对。',
    signals: ['对目的港费用敏感', '要求书面报价', '决策链短、拍板快', '10 月出货量将上升'],
  },
};

/* WhatsApp 授权 / CAMS 状态 */
window.MC_WA = {
  capability: { state: 'active', account: '+86 755 8899 0120', waba: 'Huanyu Logistics WABA', verified: true },
  authAttempt: { id: 'wa-att-1', state: 'authorized', mode: 'BUSINESS_APP_COEXISTENCE', at: '2026-09-20T11:20:00+08:00' },
  phones: [
    { id: 'ph1', num: '+86 755 8899 0120', state: 'verified', name: '主号', quality: 'GREEN' },
    { id: 'ph2', num: '+86 755 8899 0121', state: 'pending_verification', name: '备用号', quality: '—' },
  ],
  cams: [
    { id: 'cams1', space: 'huanyu-prod', region: 'ap-southeast-1', state: 'active', phones: 2, media: 34 },
    { id: 'cams2', space: 'huanyu-test', region: 'ap-southeast-1', state: 'active', phones: 1, media: 12 },
  ],
};

/* 企微安装实例 */
window.MC_WECOM_INST = [
  { corpId: 'ww9f2c1d8e6a4b', name: '深圳前海环宇国际物流', agents: 3, members: 68, extContacts: 214, state: 'active' },
];

/* 管理端 */
window.MC_ADMIN = {
  users: [
    { id: 'u1', name: '林一鸣', login: 'linyiming', roles: ['SALES', 'BROADCAST'], state: 'active', last: '2026-09-29 10:12' },
    { id: 'u2', name: '周敏', login: 'zhoumin', roles: ['SALES'], state: 'active', last: '2026-09-29 09:31' },
    { id: 'u3', name: '李伟', login: 'liwei', roles: ['SALES'], state: 'active', last: '2026-09-28 18:02' },
    { id: 'u4', name: '孙倩', login: 'sunqian', roles: ['ADMIN', 'BROADCAST'], state: 'active', last: '2026-09-29 08:40' },
    { id: 'u5', name: '赵磊', login: 'zhaolei', roles: ['SALES'], state: 'disabled', last: '2026-09-12 16:20' },
  ],
  roles: ['ADMIN', 'SALES', 'BROADCAST', 'TEMPLATE_REVIEWER', 'FINANCE'],
  platforms: [
    { id: 'pf1', name: '邮件', kind: 'IMAP / SMTP', state: 'active', accounts: 2 },
    { id: 'pf2', name: '企业微信', kind: '自建应用 + 通讯录', state: 'active', accounts: 1 },
    { id: 'pf3', name: 'WhatsApp', kind: 'Cloud API（WABA）', state: 'active', accounts: 1 },
    { id: 'pf4', name: '电话 / 录音网关', kind: 'SIP 中继 + ASR', state: 'warning', accounts: 1 },
    { id: 'pf5', name: 'ChatApp', kind: '模板消息投递', state: 'inactive', accounts: 0 },
  ],
};

/* 助手（示例对话） */
window.MC_ASSIST = {
  conversationId: '7f835793-2e11-4c8a-9f30-51d0c0b7a001',
  history: [
    { role: 'user', text: '帮我看下今天有哪些要回复的客户' },
    { role: 'assistant', text: '今天有 3 位客户在等你回复：\n\n1. 王建国（远洋货代）— 10:02 问悉尼目的港派送能否一起报\n2. David Miller（Oceanic Freight）— 07:48 要 FCL 费率，周四前\n3. Anna Schmidt（Nordwind）— 昨天 20:14 要求箱单改 PDF，并核对 61 kg\n\n要我按优先级排个处理顺序吗？' },
    { role: 'user', text: '把王建国那条的备注改成「澳线大客户 · 10月3柜」' },
    { role: 'assistant', text: '这条会改动联系人数据，需要你确认后才会执行。', pending: true },
  ],
};
