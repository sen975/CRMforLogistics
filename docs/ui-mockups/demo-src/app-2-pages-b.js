/* ============ Part 2b：群发 / 模板 / 渠道 / 企微 / 用户 / 电话 / 通讯录 / 话题 ============ */
(function () {
'use strict';
var A = window.MCAPP, S = A.S, esc = A.esc, ic = A.ic, chIcon = A.chIcon;
var hm = A.hm, md = A.md, secs = A.secs, call = A.call, shell = A.shell;
var PAGE = A.PAGES;
var CH = window.MC_CH;
var CHI = function (ch) { return '<span class="chi ' + CH[ch].cls + '" title="' + CH[ch].label + '">' + ic(chIcon(ch)) + '</span>'; };
var CALLST = A.CALLST;

/* ================= 群发 ================= */
PAGE.broadcasts = function () {
  call('fetchChatAppBroadcasts', { page: 1, size: 20 }, '群发 · 任务列表');
  call('fetchChatAppSendableTemplates', { channelAccountId: 'a4' }, '群发 · 可发送模板');
  var b = window.MC_BROADCASTS[0];
  call('fetchChatAppBroadcastFailures', { broadcastId: b.id }, '群发 · 失败名单');
  return '<div style="display:flex;flex-direction:column;gap:16px">' +
    '<div class="crumbs"><span>统一消息中心</span>' + ic('chev') + '<code>#/broadcasts</code>' +
    '<span style="margin-left:auto">渲染自 <code>pages/BroadcastsPage.tsx</code></span></div>' +
    '<div class="pghd"><div><h1>群发</h1><p>3 个任务；<b style="color:var(--red)">9 月澳线到港提醒有 5 条失败</b>，可对失败名单单独重发（重发不会再给已成功的人发一遍）。</p></div>' +
    '<div class="act"><button class="btn" data-act="pick" data-v="requestChatAppBroadcastReconciliation">' + ic('clock') + '请求对账</button>' +
    '<button class="btn pri" data-act="pick" data-v="createChatAppBroadcast">' + ic('plus') + '新建群发</button></div></div>' +
    '<section class="panel"><table class="tbl"><thead><tr><th>任务</th><th>渠道</th><th>模板</th><th>进度</th><th>失败</th><th>发起时间</th><th class="r">操作</th></tr></thead><tbody>' +
    window.MC_BROADCASTS.map(function (x) {
      return '<tr><td><b style="font-size:12.5px">' + esc(x.name) + '</b></td><td>' + CHI(x.ch) + '</td>' +
        '<td>' + esc(x.tpl) + '</td>' +
        '<td><div style="display:flex;align-items:center;gap:8px"><div style="flex:1;max-width:110px;height:6px;border-radius:999px;background:#eef0f3;overflow:hidden">' +
        '<i style="display:block;height:100%;width:' + (x.sent / x.total * 100).toFixed(0) + '%;background:' + (x.failed ? 'var(--amber)' : 'var(--wa)') + '"></i></div>' +
        '<span class="mono">' + x.sent + '/' + x.total + '</span></div></td>' +
        '<td>' + (x.failed ? '<span class="tag w8">' + x.failed + '</span>' : '<span class="tag good">0</span>') + '</td>' +
        '<td class="mono">' + md(x.at) + ' ' + hm(x.at) + '</td>' +
        '<td class="r">' + (x.failed ? '<button class="btn sm" data-act="pick" data-v="retryChatAppBroadcastFailures">重发失败</button>' : '<span style="color:var(--ink3)">—</span>') + '</td></tr>';
    }).join('') + '</tbody></table></section>' +
    '<section class="panel"><div class="hd"><h2>失败名单 <span>' + esc(b.name) + '</span></h2><span class="cnt hot">' + b.failures.length + '</span>' +
    '<a class="more" data-act="pick" data-v="retryChatAppBroadcastFailures">全部重发' + ic('chev') + '</a></div>' +
    '<table class="tbl"><thead><tr><th>收件号码</th><th>失败原因</th><th>时间</th><th class="r">处置</th></tr></thead><tbody>' +
    b.failures.map(function (f) {
      return '<tr><td class="mono">' + esc(f.to) + '</td><td><span class="tag w4">' + esc(f.reason) + '</span></td><td class="mono">' + f.at + '</td>' +
        '<td class="r"><button class="btn sm" data-act="pick" data-v="retryChatAppBroadcastFailures">单条重发</button></td></tr>';
    }).join('') + '</tbody></table></section></div>';
};

/* ================= 模板 ================= */
PAGE.templates = function () {
  call('fetchSharedTemplates', { page: 1 }, '模板 · 共享模板');
  call('fetchPublicTemplates', { page: 1 }, '模板 · 公共模板库');
  call('fetchMyTemplateChangeRequests', { page: 1 }, '模板 · 我的变更申请');
  var st = { approved: ['已通过', 'good'], pending: ['待同步', 'w4'], in_review: ['审核中', 'w4'], rejected: ['已驳回', 'w8'] };
  return '<div style="display:flex;flex-direction:column;gap:16px">' +
    '<div class="crumbs"><span>统一消息中心</span>' + ic('chev') + '<code>#/templates</code>' +
    '<span style="margin-left:auto">渲染自 <code>pages/TemplatesPage.tsx</code></span></div>' +
    '<div class="pghd"><div><h1>消息模板</h1><p>WhatsApp 模板要过 Meta 审核（approved / in_review / rejected），邮件模板本地生效；改已上线模板要提变更申请。</p></div>' +
    '<div class="act"><button class="btn" data-act="pick" data-v="syncSharedTemplates">' + ic('clock') + '同步共享模板</button>' +
    '<button class="btn" data-act="pick" data-v="uploadTemplateMedia">' + ic('clip') + '上传素材</button>' +
    '<button class="btn pri" data-act="pick" data-v="createSharedTemplate">' + ic('plus') + '提交新模板</button></div></div>' +
    '<div class="cards">' + window.MC_TEMPLATES.map(function (t) {
      var s = st[t.state];
      return '<div class="card"><div class="ttl">' + CHI(t.ch) + esc(t.name) + '</div>' +
        '<div class="dsc" style="font-family:var(--mono);font-size:11px;background:#f7f8fa;padding:9px 10px;border-radius:8px;white-space:pre-wrap;max-height:76px;overflow:hidden">' + esc(t.body) + '</div>' +
        '<div class="rt"><span class="tag ' + s[1] + '">' + s[0] + '</span><span class="tag gray">' + t.cat + '</span>' +
        '<span class="tag gray">' + t.lang + '</span>' +
        '<span class="sp" style="font-size:11px;color:var(--ink3)">用过 ' + t.used + ' 次</span></div></div>';
    }).join('') + '</div>' +
    '<section class="panel"><div class="hd"><h2>模板操作记录 <span>每次变更都留痕（谁、改了什么、何时）</span></h2>' +
    '<a class="more" data-act="pick" data-v="fetchTemplateOperations">查看全部' + ic('chev') + '</a></div>' +
    '<table class="tbl"><thead><tr><th>模板</th><th>操作</th><th>操作人</th><th>结果</th><th class="r">时间</th></tr></thead><tbody>' +
    [['到港提醒（ETA-3）', 'UPDATE', '林一鸣', 'approved', '09-29 09:12'],
     ['Q4 运价锁定通知', 'CREATE', '李伟', 'in_review', '09-28 17:40'],
     ['拼箱装箱单确认', 'SEND_PERMISSION', '孙倩', 'ok', '09-28 10:20'],
     ['账单与对账提醒', 'UPDATE', '孙倩', 'rejected', '09-27 11:02']].map(function (r) {
      return '<tr><td>' + esc(r[0]) + '</td><td><span class="tag gray" style="font-family:var(--mono)">' + r[1] + '</span></td>' +
        '<td>' + r[2] + '</td><td><span class="tag ' + (r[3] === 'rejected' ? 'w8' : r[3] === 'in_review' ? 'w4' : 'good') + '">' + r[3] + '</span></td>' +
        '<td class="r mono">' + r[4] + '</td></tr>';
    }).join('') + '</tbody></table></section></div>';
};

/* ================= 渠道设置 ================= */
PAGE.channels = function () {
  call('fetchChannelAccounts', null, '渠道设置 · 账号列表');
  call('fetchChannelCapabilities', null, '渠道设置 · 能力矩阵');
  call('fetchWhatsAppCapability', null, '渠道设置 · WhatsApp 能力');
  var cap = { email: '收发 + 附件 + 模板', wecom: '只读采集 + 群会话 + 通讯录', chatapp: '模板发送 + 媒体 + 回调', phone: '录音上传 + ASR 转录' };
  return shell('渠道设置', '#/settings/channels', 'pages/ChannelSettingsPage.tsx',
    '4 个渠道、5 个账号；<b>电话网关昨晚有 4 条录音转录失败</b>（失败的是转录，不是通话本身）。',
    [['pick', 'fetchChannelAccounts', 'clock', '重新拉取'], ['pick', 'triggerChannelSync', 'bolt', '触发同步']],
    '<section class="panel"><table class="tbl"><thead><tr><th>渠道</th><th>账号名</th><th>标识</th><th>凭证方式</th><th>状态</th><th>最后同步</th><th class="r">操作</th></tr></thead><tbody>' +
    window.MC_ACCOUNTS.map(function (a) {
      return '<tr><td><span style="display:flex;align-items:center;gap:7px">' + CHI(a.channel) + CH[a.channel].label + '</span></td>' +
        '<td><b style="font-size:12.5px">' + esc(a.name) + '</b></td><td class="mono">' + esc(a.ident) + '</td>' +
        '<td>' + esc(a.creds) + '</td>' +
        '<td>' + (a.status === 'active' ? '<span class="tag good">正常</span>' : '<span class="tag w8">告警</span>') +
        (a.note ? '<div style="font-size:11px;color:var(--red);margin-top:3px">' + esc(a.note) + '</div>' : '') + '</td>' +
        '<td class="mono">' + md(a.synced) + ' ' + hm(a.synced) + '</td>' +
        '<td class="r"><button class="btn sm" data-act="pick" data-v="fetchChannelCredentials">凭证</button> ' +
        '<button class="btn sm" data-act="pick" data-v="triggerChannelSync">同步</button> ' +
        '<button class="btn sm" data-act="pick" data-v="unbindChannelAccount">解绑</button></td></tr>';
    }).join('') + '</tbody></table></section>' +
    '<div class="cards">' + ['email', 'wecom', 'chatapp', 'phone'].map(function (ch) {
      return '<div class="card"><div class="ttl">' + CHI(ch) + CH[ch].label + '</div><div class="dsc">' + cap[ch] + '</div>' +
        '<div class="rt"><span class="tag good">active</span>' +
        '<span class="sp mono" style="font-size:11px;color:var(--ink3)">' + window.MC_ACCOUNTS.filter(function (a) { return a.channel === ch; }).length + ' 个账号</span></div></div>';
    }).join('') + '</div>');
};

/* ================= 企微管理 ================= */
PAGE.wecom = function () {
  call('fetchWeComInstallations', null, '企微管理 · 安装实例');
  call('fetchWeComBinding', null, '企微管理 · 绑定状态');
  call('listWeComDirectoryMembers', { authCorpId: 'ww9f2c1d8e6a4b' }, '企微管理 · 通讯录成员');
  call('listWeComExternalContacts', { authCorpId: 'ww9f2c1d8e6a4b' }, '企微管理 · 外部联系人');
  var inst = window.MC_WECOM_INST[0];
  return shell('企业微信管理', '#/settings/wecom', 'pages/WeComManagementPage.tsx',
    '自建应用已授权；企业微信渠道是<b>只读采集</b> —— 群消息与外部联系人消息只能看，不能从本系统发出。',
    [['pick', 'syncWeComDirectoryProfiles', 'bolt', '同步通讯录'], ['pick', 'fetchWeComInstallations', 'clock', '重新拉取']],
    '<section class="panel"><div class="hd"><h2>安装实例</h2><span class="cnt">1</span></div>' +
    '<table class="tbl"><thead><tr><th>企业</th><th>corpId</th><th>应用</th><th>成员</th><th>外部联系人</th><th>状态</th><th class="r">操作</th></tr></thead><tbody>' +
    '<tr><td><b style="font-size:12.5px">' + esc(inst.name) + '</b></td><td class="mono">' + esc(inst.corpId) + '</td>' +
    '<td>' + inst.agents + ' 个</td><td>' + inst.members + '</td><td>' + inst.extContacts + '</td><td><span class="tag good">active</span></td>' +
    '<td class="r"><button class="btn sm" data-act="pick" data-v="createWeComAvatarAuthorization">头像授权</button> ' +
    '<button class="btn sm" data-act="pick" data-v="unbindWeCom">解绑</button></td></tr>' +
    '</tbody></table></section>' +
    '<div class="cards">' +
    [['通讯录成员', 'listWeComDirectoryMembers', inst.members + ' 人'],
     ['外部联系人', 'listWeComExternalContacts', inst.extContacts + ' 位'],
     ['客户群', 'searchWeComCustomerGroups', '3 个'],
     ['部门', 'listWeComDepartments', '6 个'],
     ['标签', 'listWeComTags', '11 个'],
     ['客户动态', 'listWeComContactEvents', '近 24h 7 条']].map(function (x) {
      return '<div class="card" data-act="pick" data-v="' + x[1] + '" style="cursor:pointer"><div class="ttl">' + ic('wecom') + x[0] + '</div>' +
        '<div class="dsc" style="font-family:var(--mono);font-size:11px">' + x[1] + '</div>' +
        '<div class="rt"><span class="tag gray">' + x[2] + '</span><span class="sp" style="color:var(--ink3)">' + ic('chev') + '</span></div></div>';
    }).join('') + '</div>' +
    '<section class="panel"><div class="hd"><h2>外部联系人</h2><span class="cnt">' + window.MC_ADDRBOOK.wecom.length + '</span></div>' +
    '<table class="tbl"><thead><tr><th>昵称</th><th>external_userid</th><th>组织</th><th>匹配联系人</th><th class="r">操作</th></tr></thead><tbody>' +
    window.MC_ADDRBOOK.wecom.map(function (a) {
      return '<tr><td><b style="font-size:12.5px">' + esc(a.name) + '</b></td><td class="mono">' + esc(a.addr) + '</td>' +
        '<td>' + esc(a.org) + '</td>' +
        '<td>' + (a.matched === '—' ? '<span class="tag w4">未匹配</span>' : '<span class="tag good">' + esc(a.matched) + '</span>') + '</td>' +
        '<td class="r"><button class="btn sm" data-act="pick" data-v="updateWeComExternalContactRemark">改备注</button> ' +
        '<button class="btn sm" data-act="pick" data-v="getWeComExternalContact">详情</button></td></tr>';
    }).join('') + '</tbody></table></section>');
};

/* ================= 用户管理 ================= */
PAGE.users = function () {
  call('fetchAdminUsers', { page: 1, size: 20 }, '用户管理 · 用户列表');
  call('fetchAccountRoles', null, '用户管理 · 角色清单');
  var ROLE_DESC = { ADMIN: '全量权限，可进平台管理', SALES: '联系人 / 会话 / 发送', BROADCAST: '群发与模板', TEMPLATE_REVIEWER: '模板变更审批', FINANCE: '账单与对账' };
  return shell('用户管理', '#/settings/users', 'pages/UserManagementPage.tsx',
    '5 个账号，其中 1 个已停用；只有 ADMIN 能进平台管理（<code>AdminGuard</code> 拦住，不是靠前端隐藏菜单）。',
    [['pick', 'fetchAdminUsers', 'clock', '重新拉取'], ['pick', 'replaceAdminUserRoles', 'shield', '改角色（试调用）']],
    '<section class="panel"><table class="tbl"><thead><tr><th>姓名</th><th>登录名</th><th>角色</th><th>状态</th><th>最后登录</th><th class="r">操作</th></tr></thead><tbody>' +
    window.MC_ADMIN.users.map(function (u) {
      return '<tr><td><div style="display:flex;align-items:center;gap:8px"><span class="av t7" style="width:26px;height:26px;border-radius:8px;font-size:11px">' + esc(u.name.slice(0, 1)) + '</span>' +
        '<b style="font-size:12.5px">' + esc(u.name) + '</b></div></td><td class="mono">' + esc(u.login) + '</td>' +
        '<td><span class="chips">' + u.roles.map(function (r) { return '<span class="chip' + (r === 'ADMIN' ? ' on' : '') + '">' + r + '</span>'; }).join('') + '</span></td>' +
        '<td>' + (u.state === 'active' ? '<span class="tag good">启用</span>' : '<span class="tag gray">停用</span>') + '</td>' +
        '<td class="mono">' + esc(u.last) + '</td>' +
        '<td class="r"><button class="btn sm" data-act="pick" data-v="resetAdminUserPassword">重置密码</button></td></tr>';
    }).join('') + '</tbody></table></section>' +
    '<div class="cards">' + window.MC_ADMIN.roles.map(function (r) {
      return '<div class="card"><div class="ttl">' + ic('shield') + r + '</div><div class="dsc">' + ROLE_DESC[r] + '</div>' +
        '<div class="rt"><span class="tag gray">' + window.MC_ADMIN.users.filter(function (u) { return u.roles.indexOf(r) >= 0; }).length + ' 人</span></div></div>';
    }).join('') + '</div>');
};

/* ================= 电话仓库 ================= */
PAGE.phone = function () {
  call('fetchPhoneRepository', { page: 1, size: 20 }, '电话仓库 · 号码列表');
  call('fetchCallRecord', { id: 'call1' }, '电话仓库 · 通话详情');
  call('fetchTimeline', { contactId: 'c1' }, '电话仓库 · 通话时间线');
  return shell('电话仓库', '#/phone-repository', 'pages/PhoneRepositoryPage.tsx',
    '5 个号码；录音上传后先排队转录，转录失败可重试 —— <b>重试不会再打一次电话</b>，只重跑转录。',
    [['pick', 'createCallRecord', 'plus', '上传录音'], ['pick', 'fetchPhoneRepository', 'clock', '重新拉取']],
    '<section class="panel"><table class="tbl"><thead><tr><th>号码</th><th>标签</th><th>绑定联系人</th><th>通话数</th><th>最后通话</th><th class="r">操作</th></tr></thead><tbody>' +
    window.MC_PHONE_REPO.map(function (p) {
      return '<tr><td class="mono">' + esc(p.num) + '</td><td>' + esc(p.label) + '</td><td>' + esc(p.bind) + '</td>' +
        '<td class="mono">' + p.calls + '</td><td class="mono">' + esc(p.last) + '</td>' +
        '<td class="r"><button class="btn sm" data-act="pick" data-v="fetchCallRecord">通话详情</button> ' +
        '<button class="btn sm" data-act="pick" data-v="bindPhoneContact">绑定</button></td></tr>';
    }).join('') + '</tbody></table></section>' +
    '<section class="panel"><div class="hd"><h2>最近通话与转录</h2><span class="cnt">' + window.MC_CALLS.length + '</span>' +
    '<a class="more" data-act="pick" data-v="reviseTranscript">修正转录' + ic('chev') + '</a></div>' +
    '<table class="tbl"><thead><tr><th>方向</th><th>号码</th><th>时长</th><th>状态</th><th>备注</th><th>转录摘录</th><th class="r">时间</th></tr></thead><tbody>' +
    window.MC_CALLS.map(function (r) {
      return '<tr><td><span class="tag ' + (r.dir === 'in' ? 'gray' : 'good') + '">' + (r.dir === 'in' ? '呼入' : '呼出') + '</span></td>' +
        '<td class="mono">' + esc(r.phone) + '</td><td class="mono">' + secs(r.secs) + '</td>' +
        '<td><span class="tag ' + (r.state === 'completed' ? 'good' : r.state === 'processing' ? 'w4' : 'w8') + '">' + CALLST[r.state] + '</span></td>' +
        '<td>' + esc(r.note || '—') + '</td>' +
        '<td style="max-width:300px;font-size:11.5px;color:var(--ink2)">' + esc(r.transcript.slice(0, 40)) + '…</td>' +
        '<td class="r mono">' + md(r.at) + ' ' + hm(r.at) + '</td></tr>';
    }).join('') + '</tbody></table></section>');
};

/* ================= 渠道通讯录 ================= */
PAGE.addrbook = function (args) {
  var ch = args[0] || S.addrCh || 'email';
  if (!CH[ch]) ch = 'email';
  S.addrCh = ch;
  call('fetchChannelAddressBook', { channel: ch }, '渠道通讯录 · 列表');
  call('fetchContacts', { channel: ch }, '渠道通讯录 · 匹配联系人');
  var rows = window.MC_ADDRBOOK[ch] || [];
  var matched = rows.filter(function (r) { return r.matched !== '—'; }).length;
  return shell('渠道通讯录 · ' + CH[ch].label, '#/address-book/' + ch, 'pages/ChannelAddressBookPage.tsx',
    rows.length + ' 条记录，' + matched + ' 条已匹配到联系人，<b>' + (rows.length - matched) + ' 条未匹配</b>（可手动挂靠，也可以保持孤立 —— 孤立身份不建联系人）。',
    [['pick', 'createManualChannelContact', 'plus', '手动新增'], ['pick', 'fetchChannelAddressBook', 'clock', '重新拉取']],
    '<section class="panel"><div class="hd"><span style="display:flex;gap:6px">' +
    ['email', 'wecom', 'chatapp', 'phone'].map(function (c) {
      return '<button class="cf ' + (c === ch ? 'on' : '') + '" data-go="#/address-book/' + c + '">' + CH[c].label + '</button>';
    }).join('') + '</span><span class="cnt">' + rows.length + '</span></div>' +
    '<table class="tbl"><thead><tr><th>名称</th><th>地址 / 号码</th><th>组织</th><th>匹配到的联系人</th><th>来源</th><th class="r">操作</th></tr></thead><tbody>' +
    rows.map(function (a) {
      return '<tr><td><b style="font-size:12.5px">' + esc(a.name) + '</b></td><td class="mono">' + esc(a.addr) + '</td>' +
        '<td>' + esc(a.org) + '</td>' +
        '<td>' + (a.matched === '—' ? '<span class="tag w4">未匹配</span>' : '<span class="tag good">' + esc(a.matched) + '</span>') + '</td>' +
        '<td>' + esc(a.src) + '</td>' +
        '<td class="r">' + (a.matched === '—'
          ? '<button class="btn sm" data-act="pick" data-v="createManualChannelContact">挂靠</button>'
          : '<button class="btn sm" data-go="#/conversations/contact/c1">打开会话</button>') +
        ' <button class="btn sm" data-act="pick" data-v="deleteManualChannelContact">删除</button></td></tr>';
    }).join('') + '</tbody></table></section>');
};

/* ================= Topic 仓库 ================= */
PAGE.topic = function () {
  call('fetchTopicRepository', { page: 1, size: 20 }, 'Topic 仓库 · 列表');
  call('fetchTopicInboxRequests', null, 'Topic 仓库 · 入库申请');
  var all = Object.keys(window.MC_TOPICS).reduce(function (a, k) {
    return a.concat(window.MC_TOPICS[k].map(function (t) { t.ownerKey = k; return t; }));
  }, []);
  var pend = all.filter(function (t) { return t.state === 'pending'; }).length;
  return shell('Topic 仓库', '#/topic-repository', 'pages/TopicRepositoryPage.tsx',
    all.length + ' 个话题，其中 <b>' + pend + ' 个待复核</b> —— AI 不确定归属时留给人确认，不会自己拍板。',
    [['pick', 'fetchTopicRepository', 'clock', '重新拉取'], ['pick', 'applyManualReview', 'ok', '应用人工复核']],
    '<section class="panel"><div class="hd"><h2>话题总览</h2><span class="cnt">' + all.length + '</span>' +
    '<a class="more" data-act="pick" data-v="approveTopicStore">入库申请 1' + ic('chev') + '</a></div>' +
    '<table class="tbl"><thead><tr><th>话题</th><th>归属联系人</th><th>摘要</th><th>置信</th><th>来源</th><th>状态</th><th class="r">操作</th></tr></thead><tbody>' +
    all.map(function (t) {
      var c = window.MC_CONTACTS.filter(function (x) { return x.id === t.ownerKey; })[0] || window.MC_CONTACTS[0];
      return '<tr><td><b style="font-size:12.5px">' + esc(t.name) + '</b></td>' +
        '<td><span style="display:flex;align-items:center;gap:7px"><span class="av ' + c.tone + '" style="width:24px;height:24px;border-radius:7px;font-size:10.5px">' + esc(c.initials) + '</span>' + esc(c.displayName) + '</span></td>' +
        '<td style="max-width:300px;font-size:11.5px;color:var(--ink2)">' + esc(t.desc) + '</td>' +
        '<td class="mono">' + t.conf + '</td><td class="mono">' + t.src + ' 条</td>' +
        '<td>' + (t.state === 'pending' ? '<span class="tag w4">待复核</span>' : '<span class="tag good">已确认</span>') + '</td>' +
        '<td class="r"><button class="btn sm" data-act="pick" data-v="previewTopicFusion">合并预演</button> ' +
        '<button class="btn sm" data-act="pick" data-v="storeTopic">入库</button></td></tr>';
    }).join('') + '</tbody></table></section>' +
    '<section class="panel"><div class="hd"><h2>入库申请</h2><span class="cnt">1</span></div>' +
    '<table class="tbl"><thead><tr><th>话题</th><th>申请人</th><th>状态</th><th class="r">操作</th></tr></thead><tbody>' +
    '<tr><td>目的港派送报价</td><td>王建国（远洋货代）</td><td><span class="tag w4">待审批</span></td>' +
    '<td class="r"><button class="btn sm" data-act="pick" data-v="approveTopicStore">通过</button> ' +
    '<button class="btn sm" data-act="pick" data-v="rejectTopicStore">驳回</button></td></tr>' +
    '</tbody></table></section>');
};

})();
