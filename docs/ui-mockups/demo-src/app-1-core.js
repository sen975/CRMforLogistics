/* ============ 统一消息中心 · 可进入 Demo（v5）· Part 1：核心 ============
   1. 路由层：复刻 frontend/src/router.tsx 的真实路由表（hash 驱动）
   2. 数据层：所有取数都经过 API 层 —— 按真实注册表补全 URL、记一条请求日志、返回演示数据
   3. 壳：深色顶栏 + 左栏
   数据是假的，**接口面是真的**（从 frontend/src/api/endpoints.ts 解析而来）。 */

(function () {
'use strict';

var NOW = new Date('2026-09-29T10:30:00+08:00').getTime();

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}
function pad(n) { return n < 10 ? '0' + n : '' + n; }
function T(iso) { return new Date(iso).getTime(); }
function hm(iso) { var d = new Date(iso); return pad(d.getHours()) + ':' + pad(d.getMinutes()); }
function md(iso) { var d = new Date(iso); return (d.getMonth() + 1) + '月' + d.getDate() + '日'; }
function rel(iso) {
  var m = Math.round((NOW - T(iso)) / 60000);
  if (m < 1) return '刚刚';
  if (m < 60) return m + ' 分钟前';
  var h = Math.round(m / 60);
  if (h < 24) return h + ' 小时前';
  return Math.round(h / 24) + ' 天前';
}
function railTime(iso) {
  var d = new Date(iso), t = T(iso);
  if (NOW - t < 864e5 && new Date(NOW).getDate() === d.getDate()) return hm(iso);
  if (NOW - t < 1728e5) return '昨天';
  return (d.getMonth() + 1) + '/' + d.getDate();
}
function secs(s) { var m = Math.floor(s / 60); return m + ':' + pad(Math.floor(s % 60)); }
function ms(n) { return n >= 1000 ? (n / 1000).toFixed(1) + 's' : n + 'ms'; }

var P = {
  home:  'M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  chat:  'M21 11.5a8.4 8.4 0 0 1-8.5 8.4 8.7 8.7 0 0 1-3.9-.9L3 21l1.9-5.6A8.4 8.4 0 0 1 12.5 3 8.4 8.4 0 0 1 21 11.5z',
  cal:   'M8 2v4M16 2v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z',
  user:  'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM22 21v-2a4 4 0 0 0-3-3.9',
  doc:   'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M9 13h6M9 17h4',
  send:  'M22 2L11 13M22 2l-7 20-4-9-9-4z',
  mega:  'M3 11v2a1 1 0 0 0 1 1h3l4 4V6L7 10H4a1 1 0 0 0-1 1zM16 8a5 5 0 0 1 0 8M19 5a9 9 0 0 1 0 14',
  phone: 'M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2 4.2 2 2 0 0 1 4 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.1a2 2 0 0 1 2.1-.5c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z',
  book:  'M4 19.5A2.5 2.5 0 0 1 6.5 17H20M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z',
  tag:   'M20.6 13.4L12 22l-9-9V3h10l7.6 7.6a2 2 0 0 1 0 2.8zM7.5 7.5h.01',
  gear:  'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z',
  search:'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.3-4.3',
  chev:  'M9 18l6-6-6-6',
  down:  'M6 9l6 6 6-6',
  back:  'M19 12H5M12 19l-7-7 7-7',
  x:     'M18 6L6 18M6 6l12 12',
  plus:  'M12 5v14M5 12h14',
  ok:    'M20 6L9 17l-5-5',
  clip:  'M21.4 11.05l-9.2 9.2a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 0 1 5.7 5.7l-9.2 9.2a2 2 0 0 1-2.8-2.8l8.5-8.5',
  img:   'M3 3h18v18H3zM8.5 8.5h.01M21 15l-5-5L5 21',
  mail:  'M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM22 7l-10 6L2 7',
  wa:    'M21 11.5a8.4 8.4 0 0 1-8.5 8.4 8.7 8.7 0 0 1-3.9-.9L3 21l1.9-5.6A8.4 8.4 0 0 1 12.5 3 8.4 8.4 0 0 1 21 11.5z',
  wecom: 'M20 2H4a2 2 0 0 0-2 2v18l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zM8 11h.01M12 11h.01M16 11h.01',
  spark: 'M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z',
  flow:  'M6 3v12M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 9v3a3 3 0 0 1-3 3H9a3 3 0 0 0-3 3v.5',
  code:  'M16 18l6-6-6-6M8 6l-6 6 6 6',
  clock: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2',
  shield:'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z',
  bell:  'M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0',
  menu:  'M3 12h18M3 6h18M3 18h18',
  layers:'M12 2L2 7l10 5 10-5zM2 17l10 5 10-5M2 12l10 5 10-5',
  link:  'M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1',
  bolt:  'M13 2L3 14h8l-1 8 10-12h-8z',
  warn:  'M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0zM12 9v4M12 17h.01',
};
function ic(n, cls) {
  return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"' + (cls ? ' class="' + cls + '"' : '') + '><path d="' + P[n] + '"/></svg>';
}
function chIcon(ch) { return ch === 'email' ? 'mail' : ch === 'wecom' ? 'wecom' : ch === 'phone' ? 'phone' : 'wa'; }

/* ---------- 接口注册表（构建时注入） ---------- */
var EPS = window.MC_ENDPOINTS || [];
var DOMAINS = window.MC_DOMAINS || [];
var BY_NAME = {};
EPS.forEach(function (e) { if (!BY_NAME[e.name]) BY_NAME[e.name] = e; });

/* ---------- 状态 ---------- */
var S = {
  hash: location.hash || '#/', page: 'dashboard', args: [],
  railOpen: false, moreOpen: false,
  apiOpen: false, apiQ: '', apiM: 'ALL', apiD: 'ALL', apiTab: 'catalog', picked: null,
  logOpen: false, selMsg: null, composeCh: null, railFilter: 'all', addrCh: 'email',
};
var LOG = [];
var LOGSEQ = 0;
/* Part 2 往这个对象上挂页面渲染函数；必须是同一个引用 */
var PAGES = window.MCAPP_PAGES = {};

function toast(msg) {
  var el = document.getElementById('toast');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(el._t);
  el._t = setTimeout(function () { el.classList.remove('show'); }, 2200);
}

/* ---------- API 层 ---------- */
var SAMPLE = {
  id: 'c1', contactId: 'c1', conversationId: '7f835793-2e11-4c8a-9f30-51d0c0b7a001',
  sourceConversationId: 'g1', accountId: 'a4', userId: 'u2', scopeId: 'cams1',
  templateId: 'tp2', templateCode: 'arrival_notice_3d', broadcastId: 'b1', operationId: 'ph2',
  previewId: 'pv_31ac', requestId: 'rq_77b2', topicId: 't1', identityId: 'i1',
  pendingActionId: 'pa_9d31', authorizationId: 'wa-att-1', authCorpId: 'ww9f2c1d8e6a4b',
  chatId: 'wrOgXKAAAAtV2nR8pLm1c', externalUserId: 'wm3Xk9QAAAtV2nR8pLm1c',
  viewerSessionId: 'vs_2a91', channel: 'email', tagId: '12', contactId_: 'c1',
};
function fill(path, params) {
  return String(path).replace(/\{([A-Za-z0-9_]+)\}/g, function (_, k) {
    var v = (params && params[k] != null) ? params[k] : SAMPLE[k];
    return v == null ? '{' + k + '}' : String(v);
  });
}
/* Part 3 挂上来的四个渲染器：用转发器调，避免 Part 1 直接引用尚不存在的函数 */
function paintApiDrawer() { if (window.MCAPP.paintApiDrawer) window.MCAPP.paintApiDrawer(); }
function paintLogBar() { if (window.MCAPP.paintLogBar) window.MCAPP.paintLogBar(); }
function paintLog() { if (window.MCAPP.paintLog) window.MCAPP.paintLog(); }
function paintApi() { if (window.MCAPP.paintApi) window.MCAPP.paintApi(); }
/* 顶栏重绘会重建 #apiwrap / #logwrap，所以四个渲染器必须在一起调 */
function paintShell() {
  paintTop(); paintRail(); paintContent(); paintApiDrawer(); paintLogBar();
  /* 接口目录页的内容由 Part 3 负责，paintContent 会把它清空 ⇒ 这里补回来 */
  if (S.page === 'api') paintApi();
}

function call(name, params, trigger) {
  var ep = BY_NAME[name];
  var path = ep ? fill(ep.path, params) : '(未注册)';
  var row = {
    id: ++LOGSEQ, name: name, method: ep ? ep.method : '?', path: path,
    domain: ep ? ep.domain : '—', label: ep ? (ep.label || name) : name,
    status: 200, cost: 3 + Math.round(Math.random() * 13), at: Date.now(),
    trigger: trigger || (pageTitle() + ' · 加载'), read: ep ? !!ep.read : true,
  };
  LOG.unshift(row);
  if (LOG.length > 120) LOG.pop();
  paintLog();
  return Promise.resolve(ep ? fixture(name, params) : { ok: false, error: 'UNKNOWN_ENDPOINT' });
}

function fixture(name, params) {
  var MC = window;
  switch (name) {
    case 'fetchAccountProfile': return { displayName: MC.MC_ME.name, role: MC.MC_ME.role, username: 'linyiming' };
    case 'fetchAccountRoles': return ['SALES', 'BROADCAST'];
    case 'fetchContacts': return { total: MC.MC_CONTACTS.length, page: 1, size: 20, records: MC.MC_CONTACTS };
    case 'fetchContact': return MC.MC_CONTACTS.filter(function (c) { return c.id === (params && params.id); })[0] || MC.MC_CONTACTS[0];
    case 'listConversations': return { total: MC.MC_CONTACTS.length + MC.MC_WECOM_GROUPS.length, records: MC.MC_CONTACTS };
    case 'fetchThread': return { items: (MC.MC_THREADS[(params && params.contactId) || 'c1'] || []).slice().reverse(), nextCursor: null };
    case 'fetchContactMemory': return MC.MC_MEMORY[(params && params.contactId) || 'c1'] || { state: 'empty', summary: '暂无记忆', signals: [] };
    case 'fetchContactTopics': return { topics: MC.MC_TOPICS[(params && params.contactId) || 'c1'] || [] };
    case 'fetchTimeline': return { items: MC.MC_THREADS[(params && params.contactId) || 'c1'] || [] };
    case 'markContactRead': return null;
    case 'fetchTodos': return MC.MC_TODOS;
    case 'fetchChannelCapabilities': return [{ channel: 'email', authStatus: 'active' }, { channel: 'wecom', authStatus: 'active' }, { channel: 'chatapp', authStatus: 'active' }, { channel: 'phone', authStatus: 'active' }];
    case 'fetchChannelAccounts': return MC.MC_ACCOUNTS;
    case 'fetchTemplates': return MC.MC_TEMPLATES;
    case 'fetchSharedTemplates': return { page: 1, size: 20, total: MC.MC_TEMPLATES.length, items: MC.MC_TEMPLATES };
    case 'fetchPublicTemplates': return { page: 1, size: 20, total: 2, items: MC.MC_TEMPLATES.filter(function (t) { return t.ch === 'chatapp'; }) };
    case 'fetchChatAppBroadcasts': return { total: MC.MC_BROADCASTS.length, items: MC.MC_BROADCASTS };
    case 'fetchChatAppBroadcastDetail': return MC.MC_BROADCASTS[0];
    case 'fetchChatAppBroadcastFailures': return { total: MC.MC_BROADCASTS[0].failures.length, items: MC.MC_BROADCASTS[0].failures };
    case 'fetchPhoneRepository': return { total: MC.MC_PHONE_REPO.length, records: MC.MC_PHONE_REPO };
    case 'fetchCallRecord': return MC.MC_CALLS.filter(function (c) { return c.id === (params && params.id); })[0] || MC.MC_CALLS[0];
    case 'fetchChannelAddressBook': return MC.MC_ADDRBOOK[(params && params.channel) || 'email'] || [];
    case 'fetchWeComInstallations': return MC.MC_WECOM_INST;
    case 'fetchWeComGroupThread': return MC.MC_WECOM_GROUPS.filter(function (g) { return g.id === (params && params.sourceConversationId); })[0] || MC.MC_WECOM_GROUPS[0];
    case 'fetchWeComGroupTopics': return { topics: MC.MC_WECOM_GROUPS[0].topics.map(function (t) { return { name: t, state: 'active' }; }) };
    case 'fetchWhatsAppCapability': return MC.MC_WA.capability;
    case 'fetchWhatsAppAccounts': return [{ accountId: 'a4', phone: '+86 755 8899 0120', state: 'connected' }];
    case 'fetchAdminWhatsAppAccounts': return [{ accountId: 'a4', phone: '+86 755 8899 0120', owner: '林一鸣', state: 'active' }];
    case 'fetchAdminCams': return MC.MC_WA.cams;
    case 'fetchAdminScopedWhatsAppAccounts': return [{ accountId: 'a4', phone: '+86 755 8899 0120', scopeId: 'cams1' }];
    case 'fetchAdminWhatsAppCallbacks': return { scopeId: 'cams1', phoneCallback: 'https://api.huanyu-logistics.com/wa/phone', accountCallback: 'https://api.huanyu-logistics.com/wa/account', verified: true };
    case 'fetchAdminWhatsAppOverview': return { accounts: 1, phones: 2, cams: 2, templates: MC.MC_TEMPLATES.length, failures24h: 5 };
    case 'fetchAdminUsers': return { total: MC.MC_ADMIN.users.length, page: 0, size: 20, records: MC.MC_ADMIN.users };
    case 'fetchTopicRepository': return { total: 9, page: 1, size: 20, items: Object.keys(MC.MC_TOPICS).reduce(function (a, k) { return a.concat(MC.MC_TOPICS[k]); }, []) };
    case 'fetchTopicInboxRequests': return [{ id: 'rq_77b2', topic: '目的港派送报价', from: '王建国', state: 'pending' }];
    case 'fetchAssistantConversations': return [{ conversationId: '7f835793-2e11-4c8a-9f30-51d0c0b7a001', title: '今天的待回复客户', at: '2026-09-29T10:14:00+08:00' }];
    case 'fetchLatestAssistantConversation': return { conversationId: '7f835793-2e11-4c8a-9f30-51d0c0b7a001' };
    case 'fetchAssistantConversation': return MC.MC_ASSIST.history;
    case 'sendAssistantMessage': return { kind: 'reply', text: '（演示环境不真正调用模型）' };
    case 'fetchManualReviewPending': return MC.MC_TOPICS.c1.filter(function (t) { return t.state === 'pending'; });
    case 'fetchManualReviewSources': return { items: (MC.MC_THREADS.c1 || []).slice(0, 3) };
    case 'fetchAdminTemplates': return { page: 1, size: 20, total: MC.MC_TEMPLATES.length, items: MC.MC_TEMPLATES };
    default: return { ok: true, note: '演示数据未覆盖该接口，返回空骨架（真实服务端会有实体结构）', endpoint: name, path: (BY_NAME[name] || {}).path };
  }
}

/* ---------- 路由表（复刻 frontend/src/router.tsx） ---------- */
var ROUTES = [
  { path: '/', comp: 'pages/HomePage.tsx', name: '工作台', key: 'dashboard', re: /^#\/$/ },
  { path: '/conversations/contact/:contactId', comp: 'pages/ConversationWorkspace.tsx → ThreadPage.tsx', name: '联系人会话工作区', key: 'thread', re: /^#\/conversations\/contact\/([^/?#]+)/ },
  { path: '/conversations/wecom-group/:sourceConversationId', comp: 'pages/ConversationWorkspace.tsx → WeComConversationPanel', name: '企微群会话工作区', key: 'wecomGroup', re: /^#\/conversations\/wecom-group\/([^/?#]+)/ },
  { path: '/thread/:contactId', comp: 'router.tsx · LegacyThreadRedirect', name: '旧链接重定向', key: 'legacy', re: /^#\/thread\/([^/?#]+)/ },
  { path: '/send', comp: 'pages/SendPage.tsx', name: '发送', key: 'send', re: /^#\/send(?:\?|$)/ },
  { path: '/broadcasts', comp: 'pages/BroadcastsPage.tsx', name: '群发', key: 'broadcasts', re: /^#\/broadcasts/ },
  { path: '/templates', comp: 'pages/TemplatesPage.tsx', name: '模板', key: 'templates', re: /^#\/templates/ },
  { path: '/settings/channels', comp: 'pages/ChannelSettingsPage.tsx', name: '渠道设置', key: 'channels', re: /^#\/settings\/channels/ },
  { path: '/settings/wecom', comp: 'pages/WeComManagementPage.tsx', name: '企业微信管理', key: 'wecom', re: /^#\/settings\/wecom/ },
  { path: '/settings/users', comp: 'pages/UserManagementPage.tsx', name: '用户管理', key: 'users', re: /^#\/settings\/users/ },
  { path: '/phone-repository', comp: 'pages/PhoneRepositoryPage.tsx', name: '电话仓库', key: 'phone', re: /^#\/phone-repository/ },
  { path: '/address-book/:channel', comp: 'pages/ChannelAddressBookPage.tsx', name: '渠道通讯录', key: 'addrbook', re: /^#\/address-book\/([^/?#]+)/ },
  { path: '/topic-repository', comp: 'pages/TopicRepositoryPage.tsx', name: 'Topic 仓库', key: 'topic', re: /^#\/topic-repository/ },
  { path: '/todo-calendar', comp: 'pages/TodoCalendarPage.tsx', name: '待办日历', key: 'todos', re: /^#\/todo-calendar/ },
  { path: '/admin', comp: 'pages/AdminHomePage.tsx', name: '管理员工作台', key: 'admin', re: /^#\/admin$/ },
  { path: '/admin/platforms', comp: 'pages/AdminPlatformsPage.tsx', name: '平台接入', key: 'adminPf', re: /^#\/admin\/platforms/ },
  { path: '/admin/whatsapp/accounts', comp: 'pages/AdminWhatsAppAccountsPage.tsx', name: 'WhatsApp 账号', key: 'adminWa', re: /^#\/admin\/whatsapp\/accounts/ },
  { path: '/admin/whatsapp/template-approvals', comp: 'pages/AdminWhatsAppTemplateApprovalsPage.tsx', name: '模板审批', key: 'adminTpl', re: /^#\/admin\/whatsapp\/template-approvals/ },
  { path: '(demo 补) #/api', comp: 'demo-src / 接口目录', name: '接口目录', key: 'api', re: /^#\/api/, demo: true },
  { path: '(demo 补) #/contacts', comp: 'components/ContactsPage（真实应用里是顶栏抽屉）', name: '联系人', key: 'contacts', re: /^#\/contacts/, demo: true },
];
function match(h) {
  for (var i = 0; i < ROUTES.length; i++) {
    var m = ROUTES[i].re.exec(h);
    if (m) return { r: ROUTES[i], args: m.slice(1) };
  }
  return { r: ROUTES[0], args: [] };
}
function pageTitle() { return match(S.hash).r.name; }
function go(hash) {
  if (S.hash === hash) { render(); return; }
  S.hash = hash;
  history.pushState(null, '', hash);
  S.selMsg = null; S.railOpen = false;
  render();
}

/* ---------- 壳：顶栏 ---------- */
var NAV = [
  { k: 'dashboard', t: '工作台', h: '#/' },
  { k: 'thread', t: '会话', h: '#/conversations/contact/c1' },
  { k: 'todos', t: '待办日历', h: '#/todo-calendar', dot: true },
  { k: 'contacts', t: '联系人', h: '#/contacts' },
  { k: 'templates', t: '模板', h: '#/templates' },
];
var MORE = [
  { t: 'broadcasts', l: '群发', h: '#/broadcasts', i: 'mega' },
  { t: 'addrbook', l: '渠道通讯录', h: '#/address-book/email', i: 'book' },
  { t: 'phone', l: '电话仓库', h: '#/phone-repository', i: 'phone' },
  { t: 'topic', l: 'Topic 仓库', h: '#/topic-repository', i: 'tag' },
  { t: 'send', l: '发送', h: '#/send', i: 'send' },
  { t: 'channels', l: '渠道设置', h: '#/settings/channels', i: 'gear' },
  { t: 'wecom', l: '企业微信管理', h: '#/settings/wecom', i: 'wecom' },
  { t: 'users', l: '用户管理', h: '#/settings/users', i: 'user' },
  { t: 'admin', l: '管理员工作台', h: '#/admin', i: 'shield' },
  { t: 'adminPf', l: '平台接入', h: '#/admin/platforms', i: 'layers' },
  { t: 'adminWa', l: 'WhatsApp 账号', h: '#/admin/whatsapp/accounts', i: 'wa' },
  { t: 'adminTpl', l: '模板审批', h: '#/admin/whatsapp/template-approvals', i: 'flow' },
];

function paintTop() {
  var cur = S.page;
  var nav = NAV.map(function (n) {
    return '<a href="' + n.h + '" data-go="' + n.h + '" class="' + (cur === n.k ? 'on' : '') + '">' + esc(n.t) +
      (n.dot ? '<i class="dot"></i>' : '') + '</a>';
  }).join('');
  var moreOn = MORE.some(function (m) { return m.t === cur; });
  var fly = MORE.map(function (m) {
    return '<a href="' + m.h + '" data-go="' + m.h + '" class="' + (cur === m.t ? 'on' : '') + '">' + ic(m.i) + esc(m.l) + '</a>';
  });
  fly.splice(5, 0, '<hr><div class="fl-t">设置</div>');
  fly.splice(9, 0, '<hr><div class="fl-t">管理端</div>');

  document.getElementById('app').innerHTML =
    '<header class="top">' +
      '<button class="railbtn" data-act="rail" aria-label="切换左栏">' + ic('menu') + '</button>' +
      '<div class="brand"><span class="mark">' + ic('chat') + '</span><span>统一消息中心</span></div>' +
      '<nav class="nav">' + nav +
        '<div class="more-wrap"><a data-act="more" class="' + (moreOn ? 'on' : '') + '">更多' + ic('down') + '</a>' +
          '<div class="flyout ' + (S.moreOpen ? 'open' : '') + '">' + fly.join('') +
            '<hr><a data-go="#/api" class="' + (cur === 'api' ? 'on' : '') + '">' + ic('code') + '接口目录</a>' +
          '</div></div>' +
      '</nav>' +
      '<div class="spacer"></div>' +
      '<label class="search">' + ic('search') + '<input id="gq" placeholder="搜索联系人或消息" aria-label="全局搜索"></label>' +
      '<button class="tbtn ' + (S.apiOpen ? 'on' : '') + '" data-act="api" title="打开接口目录抽屉">' + ic('code') + '接口目录 <span class="nb">' + EPS.length + '</span></button>' +
      '<button class="tbtn ' + (S.logOpen ? 'on' : '') + '" data-act="log" title="展开请求日志">' + ic('bolt') + '请求日志 <span class="nb' + (LOG.length ? ' hot' : '') + '">' + LOG.length + '</span></button>' +
      '<div class="who"><span class="av">' + esc(window.MC_ME.initials) + '</span><span><b>' + esc(window.MC_ME.name) + '</b><small>' + esc(window.MC_ME.role) + '</small></span></div>' +
    '</header>' +
    '<div class="body">' +
      '<aside class="rail ' + (S.railOpen ? 'open' : '') + '" id="rail"></aside>' +
      '<main class="content" id="content"></main>' +
    '</div>' +
    '<div class="scrim ' + (S.railOpen ? 'on' : '') + '" data-act="rail"></div>' +
    '<div id="apiwrap"></div><div id="logwrap"></div><div class="toast" id="toast"></div>';
}

/* ---------- 壳：左栏 ---------- */
function paintRail() {
  var el = document.getElementById('rail');
  if (!el) return;
  var b = railBody();
  el.innerHTML = '<div class="rail-hd">' +
    '<label class="rsearch">' + ic('search') + '<input id="rq" placeholder="' + esc(b.ph) + '" aria-label="左栏搜索"></label>' +
    '</div>' + b.html;
}

function railBody() {
  var k = S.page;
  if (k === 'dashboard' || k === 'thread' || k === 'wecomGroup') {
    var groups = [
      { id: 'all', l: '全部会话', n: 13, c: '#fff' },
      { id: 'awaiting_me', l: '待我回复', n: 3, c: 'var(--red)' },
      { id: 'awaiting_them', l: '等对方', n: 6, c: 'var(--phone)' },
      { id: 'archived', l: '已归档', n: 4, c: 'var(--c-ink4)' },
    ];
    var rows = groups.map(function (g) {
      return '<div class="frow ' + (S.railFilter === g.id ? 'on' : '') + '" data-act="railf" data-v="' + g.id + '"><i style="background:' + g.c + '"></i>' + esc(g.l) + '<span class="n">' + g.n + '</span></div>';
    }).join('');
    var items = window.MC_CONTACTS.filter(convFilter).map(convRow).join('') +
      (k === 'dashboard' || k === 'wecomGroup' ? '' : window.MC_WECOM_GROUPS.map(groupRow).join(''));
    return { ph: '搜索会话', html: '<div class="sec"><div class="sec-t">状态</div>' + rows + '</div><div class="rlist">' + items + '</div>' };
  }
  if (k === 'contacts') {
    var cs = window.MC_CONTACTS;
    function cnt(ch) { return cs.filter(function (c) { return c.channelTypes.indexOf(ch) >= 0; }).length; }
    return { ph: '搜索联系人', html: '<div class="sec"><div class="sec-t">分组</div>' +
      '<div class="frow on"><i style="background:#fff"></i>全部联系人<span class="n">' + cs.length + '</span></div>' +
      '<div class="frow"><i style="background:var(--d-mail)"></i>有邮件<span class="n">' + cnt('email') + '</span></div>' +
      '<div class="frow"><i style="background:var(--d-wecom)"></i>有企微<span class="n">' + cnt('wecom') + '</span></div>' +
      '<div class="frow"><i style="background:var(--d-wa)"></i>有 WhatsApp<span class="n">' + cnt('chatapp') + '</span></div>' +
      '<div class="frow"><i style="background:var(--d-phone)"></i>有电话<span class="n">' + cnt('phone') + '</span></div>' +
      '</div><div class="rlist">' + cs.map(convRow).join('') + '</div>' };
  }
  if (k === 'api') {
    var counts = {};
    EPS.forEach(function (e) { counts[e.domain] = (counts[e.domain] || 0) + 1; });
    var dr = '<div class="frow ' + (S.apiD === 'ALL' ? 'on' : '') + '" data-act="apid" data-v="ALL"><i style="background:#fff"></i>全部接口<span class="n">' + EPS.length + '</span></div>' +
      DOMAINS.map(function (d) {
        return '<div class="frow ' + (S.apiD === d ? 'on' : '') + '" data-act="apid" data-v="' + esc(d) + '"><i style="background:var(--d-mail)"></i>' + esc(d) + '<span class="n">' + counts[d] + '</span></div>';
      }).join('');
    var real = ROUTES.filter(function (r) { return !r.demo; }).length;
    return { ph: '搜索接口', html: '<div class="sec" style="max-height:62%"><div class="sec-t">接口域</div>' + dr + '</div><div class="rlist">' +
      '<div class="sec-t">两份清单</div>' +
      '<div class="frow ' + (S.apiTab === 'catalog' ? 'on' : '') + '" data-act="apitab" data-v="catalog"><i style="background:var(--d-mail)"></i>接口目录<span class="n">' + EPS.length + '</span></div>' +
      '<div class="frow ' + (S.apiTab === 'routes' ? 'on' : '') + '" data-act="apitab" data-v="routes"><i style="background:var(--d-wecom)"></i>前端路由表<span class="n">' + real + '</span></div>' +
      '<div class="sec-t">请求日志</div>' +
      '<div class="frow" data-act="log"><i style="background:var(--d-wa)"></i>本次会话已发出<span class="n">' + LOG.length + '</span></div>' +
      '</div>' };
  }
  if (k === 'todos') {
    var days = {};
    window.MC_TODOS.forEach(function (t) { days[t.date] = (days[t.date] || 0) + 1; });
    var dl = Object.keys(days).sort().map(function (d, i) {
      return '<div class="frow ' + (i === 0 ? 'on' : '') + '"><i style="background:' + (i === 0 ? 'var(--red)' : 'var(--c-ink4)') + '"></i>' + d.slice(5) + '<span class="n">' + days[d] + '</span></div>';
    }).join('');
    var tl = window.MC_TODOS.filter(function (t) { return t.date === '2026-09-29'; }).map(function (t) {
      return '<div class="item"><div class="it-b"><div class="it-mid"><span class="it-t">' + esc(t.title) + '</span><div class="it-p">' + t.time + ' · ' + (t.done ? '已完成' : '待处理') + '</div></div></div></div>';
    }).join('');
    return { ph: '搜索待办', html: '<div class="sec"><div class="sec-t">日期</div>' + dl + '</div><div class="rlist"><div class="sec-t">今天</div>' + tl + '</div>' };
  }
  if (k === 'addrbook') {
    var cl = ['email', 'wecom', 'chatapp', 'phone'].map(function (c) {
      return '<div class="frow ' + (S.addrCh === c ? 'on' : '') + '" data-act="addrch" data-v="' + c + '"><i style="background:' + window.MC_CH[c].color + '"></i>' + window.MC_CH[c].label + '<span class="n">' + (window.MC_ADDRBOOK[c] || []).length + '</span></div>';
    }).join('');
    var al = (window.MC_ADDRBOOK[S.addrCh] || []).map(function (a) {
      return '<div class="item"><div class="it-b"><div class="it-mid"><span class="it-t">' + esc(a.name) + '</span><div class="it-p">' + esc(a.addr) + '</div></div></div></div>';
    }).join('');
    return { ph: '搜索通讯录', html: '<div class="sec"><div class="sec-t">渠道</div>' + cl + '</div><div class="rlist"><div class="sec-t">' + window.MC_CH[S.addrCh].label + ' 通讯录</div>' + al + '</div>' };
  }
  var MENU = {
    send: [['#/send', '发送消息'], ['#/send?channel=email', '邮件'], ['#/send?channel=chatapp', 'WhatsApp'], ['#/send?channel=wecom', '企业微信']],
    broadcasts: [['#/broadcasts', '群发任务'], ['#/broadcasts?tab=templates', '可发送模板'], ['#/broadcasts?tab=failures', '失败名单']],
    templates: [['#/templates', '全部模板'], ['#/templates?tab=shared', '共享模板'], ['#/templates?tab=public', '公共模板库'], ['#/templates?tab=changes', '变更申请']],
    channels: [['#/settings/channels', '渠道账号'], ['#/settings/channels?tab=cap', '渠道能力'], ['#/settings/channels?tab=creds', '凭证']],
    wecom: [['#/settings/wecom', '安装实例'], ['#/settings/wecom?tab=dir', '通讯录'], ['#/settings/wecom?tab=ext', '外部联系人'], ['#/settings/wecom?tab=grp', '客户群']],
    users: [['#/settings/users', '用户列表'], ['#/settings/users?tab=roles', '角色']],
    phone: [['#/phone-repository', '号码仓库'], ['#/phone-repository?tab=calls', '通话记录'], ['#/phone-repository?tab=bind', '绑定关系']],
    topic: [['#/topic-repository', '话题总览'], ['#/topic-repository?tab=inbox', '入库申请']],
    admin: [['#/admin', '概览'], ['#/admin/platforms', '平台接入'], ['#/admin/whatsapp/accounts', 'WhatsApp 账号'], ['#/admin/whatsapp/template-approvals', '模板审批']],
    adminPf: [['#/admin/platforms', '平台列表']],
    adminWa: [['#/admin/whatsapp/accounts', '账号与分配']],
    adminTpl: [['#/admin/whatsapp/template-approvals', '待审变更']],
    legacy: [['#/conversations/contact/c1', '已重定向到会话工作区']],
  };
  var m = MENU[k] || [];
  return { ph: '搜索', html: '<div class="sec"><div class="sec-t">' + esc(pageTitle()) + '</div>' +
    m.map(function (x, i) { return '<div class="frow ' + (i === 0 ? 'on' : '') + '" data-go="' + x[0] + '"><i style="background:' + (i === 0 ? '#fff' : 'var(--c-ink4)') + '"></i>' + esc(x[1]) + '</div>'; }).join('') +
    '</div><div class="rlist"><div class="sec-t">本页会调的接口</div>' + pageEndpoints(k) + '</div>' };
}

function convFilter(c) { return S.railFilter === 'all' || c.status === S.railFilter; }
function convRow(c) {
  var dots = c.channelTypes.slice(0, 2).map(function (ch, i) {
    return '<span class="ch ' + (i === 0 ? 'a' : 'b') + ' ' + window.MC_CH[ch].dot + '"></span>';
  }).join('');
  var on = (S.page === 'thread' && S.args[0] === c.id);
  return '<div class="item ' + (on ? 'on' : '') + '" data-go="#/conversations/contact/' + c.id + '">' +
    '<div class="av ' + c.tone + '">' + esc(c.initials) + dots + '</div>' +
    '<div class="it-b"><div class="it-mid"><span class="it-t">' + esc(c.remark || c.displayName) + '</span>' +
    '<div class="it-p">' + esc(c.lastText) + '</div></div>' +
    '<div class="it-r"><time>' + railTime(c.lastMessageAt) + '</time>' +
    (c.unreadCount ? '<span class="pill-n">' + c.unreadCount + '</span>' : '') + '</div></div></div>';
}
function groupRow(g) {
  var on = (S.page === 'wecomGroup' && S.args[0] === g.id);
  return '<div class="item ' + (on ? 'on' : '') + '" data-go="#/conversations/wecom-group/' + g.id + '">' +
    '<div class="av t3">群<span class="ch a dotwecom"></span></div>' +
    '<div class="it-b"><div class="it-mid"><span class="it-t">' + esc(g.name) + '</span>' +
    '<div class="it-p">' + esc(g.text) + '</div></div>' +
    '<div class="it-r"><time>' + railTime(g.at) + '</time>' +
    (g.unread ? '<span class="pill-n">' + g.unread + '</span>' : '') + '</div></div></div>';
}

/* 本页「会调哪些接口」= 用真实 import 关系反查，不是手写 */
var PAGE_SRC = {
  thread: ['ThreadPage', 'SendForm', 'MessageBubble', 'ContactDetailPanel', 'useDetailPanel', 'useCallRecordTimeline', 'AiTopicTimeline'],
  dashboard: ['HomePage', 'AppLayout', 'useContacts'],
  send: ['SendPage', 'SendForm'], broadcasts: ['BroadcastsPage'],
  templates: ['TemplatesPage', 'templates/'], channels: ['ChannelSettingsPage'],
  wecom: ['WeComManagementPage', 'wecom/'], users: ['UserManagementPage', 'AccountPanel'],
  phone: ['PhoneRepositoryPage', 'CallRecord', 'callRecord'], topic: ['TopicRepositoryPage', 'useTopicRepository'],
  addrbook: ['ChannelAddressBook'], todos: ['TodoCalendarPage', 'todos/'],
  admin: ['AdminHomePage'], adminPf: ['AdminPlatformsPage'],
  adminWa: ['AdminWhatsAppAccountsPage', 'whatsapp/'], adminTpl: ['AdminWebhappTemplateApprovals', 'AdminWhatsAppTemplateApprovals'],
  contacts: ['ContactsPage', 'useContacts'],
};
function pageEndpoints(k) {
  var keys = PAGE_SRC[k] || [];
  var used = EPS.filter(function (e) {
    return (e.usedBy || []).some(function (f) { return keys.some(function (x) { return f.indexOf(x) >= 0; }); });
  });
  if (!used.length) return '<div class="it-p" style="padding:8px 9px;font-size:12px">该页共用 AppLayout 的共享查询</div>';
  return used.slice(0, 16).map(function (e) {
    return '<div class="frow" data-act="pick" data-v="' + esc(e.name) + '"><span class="mb ' + e.method + '" style="width:42px;height:15px;font-size:9px;padding:0">' + e.method + '</span>' +
      '<span style="font-size:11.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(e.label || e.name) + '</span></div>';
  }).join('');
}

/* ---------- 通用页头 ---------- */
function shell(title, hash, comp, desc, actions, body) {
  return '<div style="display:flex;flex-direction:column;gap:16px">' +
    '<div class="crumbs"><span>统一消息中心</span>' + ic('chev') + '<code>' + esc(hash) + '</code>' +
    '<span style="margin-left:auto">渲染自 <code>' + esc(comp) + '</code></span></div>' +
    '<div class="pghd"><div><h1>' + esc(title) + '</h1><p>' + desc + '</p></div><div class="act">' +
    (actions || []).map(function (a) {
      if (a[0] === 'go') return '<button class="btn" data-go="' + a[1] + '">' + ic(a[2]) + esc(a[3]) + '</button>';
      return '<button class="btn' + (a[4] ? ' pri' : '') + '" data-act="pick" data-v="' + a[1] + '">' + ic(a[2]) + esc(a[3]) + '</button>';
    }).join('') + '</div></div>' + body + '</div>';
}

/* ---------- 页面渲染 ---------- */
function paintContent() {
  var el = document.getElementById('content');
  if (!el) return;
  var M = match(S.hash);
  S.page = M.r.key; S.args = M.args;
  if (M.r.key === 'legacy') {
    el.className = 'content';
    el.innerHTML = '<div class="crumbs"><span>统一消息中心</span>' + ic('chev') + '<code>#/thread/' + esc(M.args[0]) + '</code></div>' +
      '<div style="display:grid;place-items:center;height:calc(100vh - 220px);gap:12px;text-align:center">' +
      '<div>' + ic('link') + '</div><h1 style="margin:0;font-size:19px">正在重定向到会话工作区…</h1>' +
      '<p style="color:var(--ink2);font-size:13px">真实实现是 <code style="font-family:var(--mono)">router.tsx</code> 里的 <code style="font-family:var(--mono)">&lt;Navigate to="/conversations/contact/:id" replace /&gt;</code>，旧书签不会 404。</p>' +
      '<button class="btn pri" id="redir">立即跳转</button></div>';
    var b = document.getElementById('redir');
    if (b) b.onclick = function () { go('#/conversations/contact/' + (M.args[0] || 'c1')); };
    el.dataset.legacy = M.args[0] || 'c1';
    setTimeout(function () {
      if (S.page === 'legacy') go('#/conversations/contact/' + (M.args[0] || 'c1'));
    }, 900);
    return;
  }
  /* 接口目录页由 Part 3 的 paintApi 负责（见 render()）——
     这里直接清空，别 fallback 到工作台（会白画一遍并多记一批请求） */
  if (M.r.key === 'api') { el.className = 'content'; el.innerHTML = ''; return; }
  var fn = PAGES[M.r.key] || PAGES.dashboard;
  el.className = 'content' + (M.r.key === 'thread' || M.r.key === 'wecomGroup' ? ' flush' : '');
  el.innerHTML = fn(M.args);
  if (M.r.key === 'thread') {
    var tl = document.getElementById('tl');
    if (tl) tl.scrollTop = tl.scrollHeight;
  }
}

function render() {
  var M = match(S.hash);
  S.page = M.r.key; S.args = M.args;
  paintShell();
}

/* ---------- 事件 ---------- */
function bind() {
  var app = document.getElementById('app');
  document.addEventListener('click', function (e) {
    var g = e.target.closest('[data-go]');
    if (g) { e.preventDefault(); go(g.getAttribute('data-go')); return; }
    var a = e.target.closest('[data-act]');
    if (!a) {
      if (S.moreOpen && !e.target.closest('.more-wrap')) { S.moreOpen = false; paintTop(); }
      return;
    }
    var act = a.getAttribute('data-act'), v = a.getAttribute('data-v');
    handle(act, v, a);
  });
  window.addEventListener('popstate', function () { S.hash = location.hash || '#/'; render(); });
  window.addEventListener('hashchange', function () { if (S.hash !== location.hash) { S.hash = location.hash || '#/'; render(); } });
  window.addEventListener('resize', function () {
    if (window.innerWidth > 1080 && S.railOpen) { S.railOpen = false; paintTop(); paintContent(); }
  });
}

function handle(act, v, el) {
  switch (act) {
    case 'rail': S.railOpen = !S.railOpen; syncRail(); break;
    case 'more': S.moreOpen = !S.moreOpen; paintShell(); break;
    case 'api': S.apiOpen = !S.apiOpen; paintShell(); break;
    case 'log': S.logOpen = !S.logOpen; paintShell(); break;
    case 'railf': S.railFilter = v; paintRail(); break;
    case 'addrch': S.addrCh = v; paintRail(); break;
    case 'apid': S.apiD = v; paintRail(); paintApi(); break;
    case 'apitab': S.apiTab = v; paintRail(); if (S.page !== 'api') go('#/api'); else paintApi(); break;
    case 'apiq':
      S.apiQ = el.value; paintApi(); break;
    case 'apim': S.apiM = v; paintApi(); break;
    case 'cmpch': S.composeCh = v; paintContent(); break;
    case 'msg': S.selMsg = (S.selMsg === v ? null : v); paintContent(); break;
    case 'call': openCall(v); break;
    case 'todo': toggleTodo(v); break;
    case 'reload': toast('已重新拉取会话（演示：又记了几条请求）'); call('fetchThread', { contactId: v }, '会话工作区 · 手动刷新'); call('fetchContact', { id: v }, '会话工作区 · 手动刷新'); break;
    case 'tabs': toast('真实应用里这是「混合时间线 / 企微会话」切换，会换掉整个时间线组件'); break;
    case 'sendsim': simulateSend(v); break;
    case 'pick': pickEndpoint(v); break;
    case 'route': go('#/conversations/contact/c1'); break;
  }
}

function syncRail() {
  var r = document.getElementById('rail'), s = document.querySelector('.scrim');
  if (r) r.classList.toggle('open', S.railOpen);
  if (s) s.classList.toggle('on', S.railOpen);
}

function toggleTodo(id) {
  var t = window.MC_TODOS.filter(function (x) { return x.id === id; })[0];
  if (!t) return;
  t.done = !t.done;
  call('updateTodoApi', { id: id, done: t.done }, '待办 · ' + (t.done ? '标记完成' : '取消完成'));
  paintContent();
  toast(t.done ? '已完成「' + t.title + '」' : '已恢复「' + t.title + '」');
}

function simulateSend(ch) {
  if (ch === 'email') {
    call('sendEmail', { to: 'bruce.wang@yuanyang-scm.com' }, '会话工作区 · 发送邮件（人工入口）');
    toast('POST /api/send/email · 200 · 已入队，状态 pending → sent');
  } else if (ch === 'chatapp') {
    call('sendChatApp', { channelAccountId: 'a4' }, '会话工作区 · 按模板发送');
    toast('POST /api/chatapp/send/template · 200 · 模板消息已提交');
  } else {
    call('sendWeCom', { corpId: 'ww9f2c1d8e6a4b', agentId: '1000011' }, '会话工作区 · 发送企微消息');
    toast('POST /api/wecom/send · 200 · 已提交企微网关');
  }
}

/* 试调用：真的会记一条日志 + 弹出响应预览 */
function pickEndpoint(name) {
  S.picked = name;
  if (S.page !== 'api') {
    call(name, null, pageTitle() + ' · 试调用');
    toast('已试调用 ' + name + ' · 看底部「请求日志」');
    if (!S.logOpen) { S.logOpen = true; paintLogBar(); }
    paintRail();
    return;
  }
  call(name, null, '接口目录 · 试调用');
  paintApi();
  toast('已试调用 ' + name);
}

function openCall(id) {
  var r = window.MC_CALLS.filter(function (x) { return x.id === id; })[0];
  if (!r) return;
  call('fetchCallRecord', { id: id }, '通话详情 · 打开');
  call('fetchTimeline', { contactId: r.contactId }, '通话详情 · 时间线');
  toast('GET /api/v1/call-records/' + id + ' · 200 · 转录 ' + (r.state === 'completed' ? '已完成' : '处理中'));
}

/* 暴露给 Part 2/3 */
window.MCAPP = {
  S: S, LOG: LOG, ROUTES: ROUTES, EPS: EPS, DOMAINS: DOMAINS, BY_NAME: BY_NAME,
  esc: esc, ic: ic, chIcon: chIcon, hm: hm, md: md, secs: secs, ms: ms, rel: rel, T: T, NOW: NOW,
  call: call, fixture: fixture, fill: fill, go: go, render: render, bind: bind, shell: shell,
  toast: toast, paintTop: paintTop, paintRail: paintRail, paintContent: paintContent, handle: handle,
  OUTST: {
    sent: { l: '已发送', c: 'gray' }, delivered: { l: '已送达', c: 'good' }, read: { l: '已读', c: 'good' },
    pending: { l: '排队中', c: 'w4' }, failed: { l: '发送失败', c: 'w8' }, received: null,
  },
  CALLST: { completed: '已完成', processing: '转录中', queued: '排队中', failed: '失败' },
  PAGES: PAGES, paintApi: null, paintApiDrawer: null, paintLogBar: null,
};
window.MCAPP.P = P;
})();
