/* ============ Part 2a：工作台 / 会话工作区 / 联系人 / 待办 ============ */
(function () {
'use strict';
var A = window.MCAPP, S = A.S, esc = A.esc, ic = A.ic, chIcon = A.chIcon;
var hm = A.hm, md = A.md, secs = A.secs, rel = A.rel, T = A.T, call = A.call, go = A.go;
var shell = A.shell, toast = A.toast;
var PAGE = A.PAGES;
var CH = window.MC_CH, CHI = function (ch) { return '<span class="chi ' + CH[ch].cls + '" title="' + CH[ch].label + '">' + ic(chIcon(ch)) + '</span>'; };

/* ================= 工作台 ================= */
PAGE.dashboard = function () {
  call('fetchContacts', { page: 1, size: 20 }, '工作台 · 联系人统计');
  call('fetchChannelCapabilities', null, '工作台 · 渠道能力');
  call('fetchTodos', null, '工作台 · 今日待办');
  call('fetchChatAppBroadcasts', null, '工作台 · 群发概览');

  var waiting = window.MC_CONTACTS.filter(function (c) { return c.status === 'awaiting_me'; });
  var needs = waiting.map(function (c) {
    var th = window.MC_THREADS[c.id] || [], last = th[th.length - 1];
    return '<div class="need" data-go="#/conversations/contact/' + c.id + '">' +
      '<div class="av ' + c.tone + '">' + esc(c.initials) + '</div><div class="b">' +
      '<div class="t"><b>' + esc(c.displayName) + ' · ' + esc(c.company.slice(0, 10)) + '</b><time>' + rel(last.at) + '</time></div>' +
      '<div class="q">' + esc(last.body || last.subject || '') + '</div>' +
      '<div class="m">' + c.channelTypes.map(CHI).join('') + '<span class="tag w8">待我回复</span>' +
      '<span class="tag gray">' + esc(c.remark) + '</span>' +
      '<button class="mini" data-go="#/conversations/contact/' + c.id + '">打开会话' + ic('chev') + '</button></div></div></div>';
  }).join('');

  var todays = window.MC_TODOS.filter(function (t) { return t.date === '2026-09-29'; });
  var todos = todays.map(function (t) {
    return '<div class="td ' + (t.done ? 'done' : '') + '" data-act="todo" data-v="' + t.id + '">' +
      '<span class="box">' + ic('ok') + '</span><span class="txt">' + esc(t.title) + '</span>' +
      '<span class="due ' + (t.hot ? 'hot' : '') + '">' + t.time + '</span></div>';
  }).join('');

  var tot = { email: 6, chatapp: 4, wecom: 2, phone: 1 }, sum = 13;
  var bar = ['email', 'chatapp', 'wecom', 'phone'].map(function (ch, i, arr) {
    var r = i === 0 ? 'border-radius:999px 0 0 999px' : (i === arr.length - 1 ? 'border-radius:0 999px 999px 0' : '');
    return '<i style="width:' + (tot[ch] / sum * 100).toFixed(1) + '%;background:' + CH[ch].color + ';' + r + '"></i>';
  }).join('');

  return '<div style="display:flex;flex-direction:column;gap:16px">' +
    '<div class="crumbs"><span>统一消息中心</span>' + ic('chev') + '<code>#/</code>' +
    '<span style="margin-left:auto">渲染自 <code>pages/HomePage.tsx</code> · 点击任意行进入会话</span></div>' +
    '<div class="greet"><div><h1>上午好，' + esc(window.MC_ME.name) + '</h1>' +
      '<p>今天有 <b>3 位客户</b>在等你回复，<b>1 条</b>群发任务部分失败，10/12 船期截港还剩 11 天。</p></div>' +
      '<div class="act"><button class="btn" data-go="#/todo-calendar">' + ic('cal') + '待办日历</button>' +
      '<button class="btn pri" data-go="#/conversations/contact/c1">' + ic('chat') + '打开待回复会话</button></div></div>' +
    '<section class="panel"><div class="stats">' +
      '<div class="stat"><div class="lab">' + ic('user') + '联系人</div><div class="v"><b>' + window.MC_CONTACTS.length + '</b><em class="good">+2 本周</em></div></div>' +
      '<div class="stat alert"><div class="lab"><i style="background:var(--red)"></i>待你回复</div><div class="v"><b>3</b></div></div>' +
      '<div class="stat"><div class="lab"><i style="background:var(--wecom)"></i>会话</div><div class="v"><b>13</b><em>含 3 个企微群</em></div></div>' +
      '<div class="stat"><div class="lab">渠道状态</div><div class="v"><span class="chs">' +
        ['email', 'chatapp', 'wecom', 'phone'].map(function (ch) { return '<i style="background:' + CH[ch].color + '" title="' + CH[ch].label + '"></i>'; }).join('') +
      '</span><em>1 告警</em></div></div></div></section>' +
    '<div class="cols"><div class="stack">' +
      '<section class="panel"><div class="hd"><h2>等你回复 <span>按最后来信时间</span></h2><span class="cnt hot">3</span>' +
        '<a class="more" data-go="#/conversations/contact/c1">全部会话' + ic('chev') + '</a></div>' +
        '<div class="need-wrap">' + needs + '</div></section>' +
      '<section class="panel"><div class="hd"><h2>会话渠道构成</h2><span class="cnt">13</span></div>' +
        '<div class="chan"><div class="stackbar">' + bar + '</div><div class="lgds">' +
        ['email', 'chatapp', 'wecom', 'phone'].map(function (ch) {
          return '<span class="lgd"><i style="background:' + CH[ch].color + '"></i>' + CH[ch].label + ' <b>' + tot[ch] + '</b></span>';
        }).join('') + '</div></div></section>' +
    '</div><div class="stack">' +
      '<section class="panel"><div class="hd"><h2>今日待办</h2><span class="cnt">' + todays.length + '</span>' +
        '<a class="more" data-go="#/todo-calendar">日历' + ic('chev') + '</a></div><div class="todo">' + todos + '</div></section>' +
      '<section class="panel"><div class="hd"><h2>群发任务</h2><span class="cnt hot">1 异常</span>' +
        '<a class="more" data-go="#/broadcasts">全部' + ic('chev') + '</a></div><div class="todo">' +
        window.MC_BROADCASTS.map(function (b) {
          return '<div class="td" data-go="#/broadcasts"><span class="box" style="border-style:dashed"></span>' +
            '<span class="txt">' + esc(b.name) + '</span>' +
            '<span class="due ' + (b.failed ? 'hot' : '') + '">' + b.sent + '/' + b.total + (b.failed ? ' · 失败 ' + b.failed : '') + '</span></div>';
        }).join('') + '</div></section>' +
    '</div></div></div>';
};

/* ================= 会话工作区（联系人） ================= */
var OUTST = A.OUTST, CALLST = A.CALLST;

PAGE.thread = function (args) {
  var id = args[0] || 'c1';
  var c = window.MC_CONTACTS.filter(function (x) { return x.id === id; })[0] || window.MC_CONTACTS[0];
  call('fetchContact', { id: id }, '会话工作区 · 联系人详情');
  call('fetchThread', { contactId: id, limit: 20 }, '会话工作区 · 加载时间线');
  call('markContactRead', { id: id }, '会话工作区 · 标记已读');
  call('fetchContactMemory', { contactId: id }, '会话工作区 · 联系人记忆');
  call('fetchContactTopics', { contactId: id }, '会话工作区 · AI 话题');
  call('fetchWeComContactThread', { contactId: id, limit: 50 }, '会话工作区 · 企微会话');

  /* 时间线 = 消息 + 通话。MC_THREADS 里 kind:'call' 的是通话占位项，真实通话数据在
     MC_CALLS（带号码 / 状态 / 转录）；按 callId 去重：占位项有对应通话记录就跳过
     （交给 MC_CALLS 渲染），没有就原地渲染自己 —— 避免同一次通话冒出两条。 */
  var callById = {};
  window.MC_CALLS.forEach(function (x) { callById[x.id] = x; });
  var msgs = (window.MC_THREADS[id] || []).filter(function (m) {
    return !(m.kind === 'call' && callById[m.callId]);
  });
  var calls = window.MC_CALLS.filter(function (x) { return x.contactId === id; })
    .map(function (x) { return { id: x.id, dir: x.dir, ch: 'phone', kind: 'call', at: x.at, call: x }; });
  var all = msgs.concat(calls).sort(function (a, b) { return T(a.at) - T(b.at); });
  var lastDay = null, flow = all.map(function (m) {
    var d = md(m.at), sep = d !== lastDay ? '<div class="daysep">' + d + '</div>' : '';
    lastDay = d;
    return sep + (m.kind === 'call' ? callBub(m.call || m) : msgBub(m));
  }).join('');

  var chs = c.channelTypes;
  var act = S.composeCh && chs.indexOf(S.composeCh) >= 0 ? S.composeCh : chs[0];
  var tabs = chs.map(function (ch) {
    return '<button class="t ' + (act === ch ? 'on' : '') + '" data-act="cmpch" data-v="' + ch + '">' + ic(chIcon(ch)) + CH[ch].label + '</button>';
  }).join('');

  return '<div class="thread">' +
    '<div class="th-hd">' +
      '<button class="icon-btn" data-go="#/" title="返回工作台">' + ic('back') + '</button>' +
      '<div class="av ' + c.tone + '">' + esc(c.initials) + '</div>' +
      '<div><div class="nm">' + esc(c.remark || c.displayName) + '</div>' +
      '<div class="sub">' + esc(c.company) + '<span>·</span>' + esc(c.region) + '<span>·</span>' + esc(c.owner) + ' 负责</div></div>' +
      '<div class="act">' +
        '<span class="seg"><button class="on">混合时间线</button><button data-act="tabs" data-v="wecom">企微会话</button><button data-act="tabs" data-v="phone">通话</button></span>' +
        '<button class="btn sm" data-act="reload" data-v="' + id + '">' + ic('clock') + '刷新</button>' +
        '<button class="btn sm" data-act="api">' + ic('code') + '本页接口</button>' +
      '</div></div>' +
    '<div class="th-body"><div class="tl" id="tl">' + (flow || '<div class="empty">该联系人暂无消息</div>') + '</div>' +
      ctxPanel(c) + '</div>' +
    '<div class="compose">' +
      '<div class="cmp-tabs">' + tabs + '<span class="who">' + ic('link') + '收件人不是模型的参数 —— 服务端按调用方身份从档案解析</span></div>' +
      (CH_COMPOSE[act] || function () { return ''; })(c) +
    '</div></div>';
};

function msgBub(m) {
  var isOut = m.dir === 'out', sel = S.selMsg === m.id;
  var atts = (m.atts || []).map(function (a) {
    return '<div class="att">' + ic(a.kind === 'image' ? 'img' : 'doc') + esc(a.name) + ' · ' + esc(a.size) + '</div>';
  }).join('');
  var body = (m.kind === 'image' && !m.body)
    ? '<div class="att">' + ic('img') + esc((m.atts[0] || {}).name || '图片') + ' · ' + esc((m.atts[0] || {}).size || '') + '</div>'
    : '<div class="tx">' + esc(m.body) + '</div>' + atts;
  var st = isOut ? OUTST[m.status] : null;
  return '<div class="msg ' + (isOut ? 'out' : '') + '">' +
    '<div class="av ' + (isOut ? 'out-av' : 't7') + '">' + (isOut ? '我' : esc(String(m.from || '').slice(0, 2))) + '</div>' +
    '<div class="bub ' + (sel ? 'sel' : '') + '" data-act="msg" data-v="' + m.id + '">' +
      (m.subject ? '<span class="subj">' + esc(m.subject) + '</span>' : '') + body +
      '<div class="meta">' + CHI(m.ch) + '<span class="tag gray">' + m.kind + '</span>' +
      (st ? '<span class="tag ' + st.c + '">' + st.l + '</span>' : '') +
      '<time>' + hm(m.at) + '</time></div></div></div>';
}
function callBub(r) {
  r = r || {};
  var isOut = r.dir === 'out';
  var st = r.state || 'completed';
  return '<div class="call ' + (isOut ? 'out' : '') + '">' +
    '<div class="av">' + ic('phone') + '</div>' +
    '<div class="card" data-act="call" data-v="' + esc(r.id || r.callId || '') + '">' +
      '<div class="row"><span class="tag ' + (r.dir === 'in' ? 'gray' : 'good') + '">' + (r.dir === 'in' ? '呼入' : '呼出') + '</span>' +
      '<b>' + esc(r.phone || '未记录号码') + '</b><span class="tag gray">' + secs(r.secs || 0) + '</span>' +
      '<span class="tag ' + (st === 'completed' ? 'good' : st === 'processing' ? 'w4' : 'w8') + '">' + (CALLST[st] || st) + '</span>' +
      '<time style="margin-left:auto;font-size:11px;color:var(--ink3)">' + hm(r.at) + '</time></div>' +
      (r.note ? '<div style="font-size:11.5px;color:var(--ink3);margin-top:6px">' + esc(r.note) + '</div>' : '') +
      '<div class="tr">' + esc(r.transcript || '（无转录）') + '</div></div></div>';
}

var CH_COMPOSE = {
  email: function (c) {
    var i = (c.identities || []).filter(function (x) { return x.channelType === 'email'; })[0];
    return '<div class="fld"><label>收件人</label><select>' + (i
      ? '<option>' + esc(i.displayName + ' <' + i.identityValue + '>') + '</option>'
      : '<option>（该联系人没有邮箱身份 → 退化成可手填的输入框）</option>') + '</select></div>' +
      '<div class="fld"><label>主题</label><input placeholder="Re: 蛇口—悉尼 订舱确认 · SO 编号 SZXSYD2610012"></div>' +
      '<div class="fld"><label>正文</label><textarea placeholder="正文…"></textarea></div>' +
      '<div class="cmp-ft"><button class="btn sm">' + ic('clip') + '附件</button>' +
      '<button class="btn sm">' + ic('doc') + '套用模板</button>' +
      '<span class="hint">POST /api/send/email · 请求体不带 contactId，发给陌生地址会落一条孤立身份</span>' +
      '<span class="r"><button class="btn sm">存草稿</button>' +
      '<button class="btn sm pri" data-act="sendsim" data-v="email">发送邮件</button></span></div>';
  },
  chatapp: function (c) {
    var i = (c.identities || []).filter(function (x) { return x.channelType === 'chatapp'; })[0];
    return '<div class="fld"><label>收件人</label><select>' + (i
      ? '<option>' + esc(i.displayName + ' ' + i.identityValue) + '</option>'
      : '<option>（该联系人没有 WhatsApp 身份 → 手填号码）</option>') + '</select></div>' +
      '<div class="fld"><label>模板</label><select><option>到港提醒（ETA-3）· approved</option><option>文件索取（箱单 / 发票）· pending</option></select></div>' +
      '<div class="fld"><label>参数</label><input value="David Miller | SZXSYD2610012 | Sydney | Nov 1"></div>' +
      '<div class="cmp-ft"><span class="hint">POST /api/chatapp/send/template · 模板 + 参数，不允许自由文本</span>' +
      '<span class="r"><button class="btn sm pri" data-act="sendsim" data-v="chatapp">按模板发送</button></span></div>';
  },
  wecom: function (c) {
    var i = (c.identities || []).filter(function (x) { return x.channelType === 'wecom'; })[0];
    return '<div class="fld"><label>收件人</label><select>' + (i
      ? '<option>' + esc(i.displayName + ' ' + i.identityValue.slice(0, 18) + '…') + '</option>'
      : '<option>（该联系人没有企微身份）</option>') + '</select></div>' +
      '<div class="fld"><label>内容</label><textarea placeholder="企微消息…"></textarea></div>' +
      '<div class="cmp-ft"><button class="btn sm">' + ic('img') + '图片</button><button class="btn sm">' + ic('doc') + '文件</button>' +
      '<span class="hint">POST /api/wecom/send · corpId / agentId / to 由档案解析，不是模型的参数</span>' +
      '<span class="r"><button class="btn sm pri" data-act="sendsim" data-v="wecom">发送企微消息</button></span></div>';
  },
  phone: function (c) {
    var i = (c.identities || []).filter(function (x) { return x.channelType === 'phone'; })[0];
    return '<div class="fld"><label>号码</label><select>' + (i
      ? '<option>' + esc(i.displayName + ' ' + i.identityValue) + '</option>'
      : '<option>（无电话身份，可去电话仓库选号）</option>') + '</select></div>' +
      '<div class="cmp-ft"><span class="hint">电话没有「发送」接口 —— 通话是录音上传后转录：POST /api/v1/call-records</span>' +
      '<span class="r"><button class="btn sm" data-act="pick" data-v="createCallRecord">上传通话录音（试调用）</button>' +
      '<button class="btn sm pri" data-go="#/phone-repository">去电话仓库</button></span></div>';
  },
};

function ctxPanel(c) {
  var mem = window.MC_MEMORY[c.id], topics = window.MC_TOPICS[c.id] || [];
  var calls = window.MC_CALLS.filter(function (x) { return x.contactId === c.id; });
  return '<aside class="ctx">' +
    '<div class="grp"><h3>' + ic('user') + '联系人</h3>' +
      kv('名称', esc(c.displayName)) + kv('备注', esc(c.remark)) + kv('公司', esc(c.company)) +
      kv('方向', esc(c.direction) + ' · ' + esc(c.region)) + kv('负责人', esc(c.owner)) +
      kv('人工标签', '<span class="chips">' + c.tags.map(function (t) { return '<span class="chip">' + esc(t) + '</span>'; }).join('') + '</span>') +
      kv('AI 标签', '<span class="chips">' + c.aiTags.map(function (t) { return '<span class="chip" style="background:var(--wecom-soft);color:var(--wecom)">' + esc(t) + '</span>'; }).join('') + '</span>') +
    '</div>' +
    '<div class="grp"><h3>' + ic('spark') + 'AI 画像</h3><div style="font-size:12px;color:var(--ink2);line-height:1.65">' + esc(c.aiProfile) + '</div></div>' +
    '<div class="grp"><h3>' + ic('link') + '渠道身份<span class="cnt">' + c.identities.length + '</span></h3>' +
      c.identities.map(function (i) {
        return kv(CHI(i.channelType), '<b style="font-size:12px">' + esc(i.displayName) + '</b>' +
          '<div style="font-family:var(--mono);font-size:11px;color:var(--ink2);word-break:break-all;margin-top:2px">' + esc(i.identityValue) + '</div>' +
          '<div style="font-size:11px;color:var(--ink3);margin-top:2px">identity_scope: ' + esc(i.scope) + ' · 已挂 ' + i.bindings + ' 条会话</div>');
      }).join('') + '</div>' +
    (mem ? '<div class="grp"><h3>' + ic('spark') + '联系人记忆<span class="cnt">' + mem.turns + ' 轮</span></h3>' +
      '<div style="font-size:12px;color:var(--ink2);line-height:1.65">' + esc(mem.summary) + '</div>' +
      '<div class="chips" style="margin-top:9px">' + mem.signals.map(function (s) { return '<span class="chip">' + esc(s) + '</span>'; }).join('') + '</div>' +
      '<div style="font-size:11px;color:var(--ink3);margin-top:9px">state ' + mem.state + ' · 更新于 ' + hm(mem.updatedAt) + '</div></div>' : '') +
    (topics.length ? '<div class="grp"><h3>' + ic('tag') + 'AI 话题<span class="cnt">' + topics.length + '</span></h3>' +
      topics.map(function (t) {
        return '<div class="tpc" data-go="#/topic-repository"><div class="n">' + esc(t.name) + '</div>' +
          '<div class="d">' + esc(t.desc) + '</div><div class="m">' +
          '<em>' + (t.state === 'pending' ? '待复核' : '已确认') + '</em><em>置信 ' + t.conf + '</em><em>' + t.src + ' 条来源</em></div></div>';
      }).join('') + '</div>' : '') +
    (calls.length ? '<div class="grp"><h3>' + ic('phone') + '通话<span class="cnt">' + calls.length + '</span></h3>' +
      calls.map(function (r) {
        return '<div class="tpc" data-act="call" data-v="' + r.id + '"><div class="n">' + (r.dir === 'in' ? '呼入' : '呼出') + ' · ' + secs(r.secs) + '</div>' +
          '<div class="d">' + esc(r.note || '（无备注）') + '</div><div class="m"><em>' + CALLST[r.state] + '</em><em>' + md(r.at) + ' ' + hm(r.at) + '</em></div></div>';
      }).join('') + '</div>' : '') +
    '</aside>';
}
function kv(k, v) {
  return '<div class="kv"><span class="k">' + k + '</span><span class="v">' + v + '</span></div>';
}

/* ================= 企微群工作区 ================= */
PAGE.wecomGroup = function (args) {
  var g = window.MC_WECOM_GROUPS.filter(function (x) { return x.id === args[0]; })[0] || window.MC_WECOM_GROUPS[0];
  call('fetchWeComGroupThread', { sourceConversationId: g.id }, '企微群工作区 · 群会话');
  call('fetchWeComGroupTopics', { sourceConversationId: g.id }, '企微群工作区 · 群话题');
  call('bootstrapWeComViewer', null, '企微群工作区 · 查看器初始化');
  call('createWeComViewerTargetSession', { targetId: g.id }, '企微群工作区 · 创建查看器会话');
  var bub = function (who, subj, text, tag, time) {
    return '<div class="msg"><div class="av t7">' + who + '</div><div class="bub">' +
      '<span class="subj">' + subj + '</span><div class="tx">' + text + '</div>' +
      '<div class="meta">' + CHI('wecom') + '<span class="tag gray">' + tag + '</span><time>' + time + '</time></div></div></div>';
  };
  return '<div class="thread">' +
    '<div class="th-hd"><button class="icon-btn" data-go="#/">' + ic('back') + '</button>' +
      '<div class="av t3">群</div><div><div class="nm">' + esc(g.name) + '</div>' +
      '<div class="sub">' + g.members + ' 位成员 · 群主 ' + esc(g.owner) + '<span>·</span>sourceConversationId: ' + esc(g.id) + '</div></div>' +
      '<div class="act"><span class="seg"><button data-act="tabs" data-v="mixed">混合时间线</button><button class="on">企微会话</button></span>' +
      '<button class="btn sm" data-act="pick" data-v="refreshWeComGroupName">' + ic('clock') + '刷新群名</button>' +
      '<button class="btn sm" data-act="pick" data-v="fetchWeComGroupTopics">' + ic('spark') + '群话题</button></div></div>' +
    '<div class="th-body"><div class="tl" id="tl"><div class="daysep">今天</div>' +
      bub('周', '装柜安排', '10/12 那条船的柜子，工厂说 9 日下午送到堆场。到时候我把进仓单拍给群里。', 'text', '09:31') +
      bub('王', '目的港派送报价', '林经理，昨天说的悉尼派送总价出来了吗？客户在催。', 'text', '09:42') +
      bub('系', '群概况变更', '群名已刷新为「澳洲线客户群 · 远洋」；新增外部联系人 1 位；消息类型同步完成。', 'event', '09:44') +
      '</div><aside class="ctx">' +
      '<div class="grp"><h3>' + ic('wecom') + '群信息</h3>' +
        kv('群名', esc(g.name)) + kv('成员', g.members + ' 位（外部 6 位）') + kv('群主', esc(g.owner)) +
        kv('消息类型', '<span class="chips"><span class="chip">text</span><span class="chip">image</span><span class="chip">file</span><span class="chip">link</span></span>') +
      '</div>' +
      '<div class="grp"><h3>' + ic('spark') + '群话题<span class="cnt">' + g.topics.length + '</span></h3>' +
        g.topics.map(function (t) { return '<div class="tpc" data-go="#/topic-repository"><div class="n">' + esc(t) + '</div><div class="m"><em>已确认</em></div></div>'; }).join('') + '</div>' +
      '<div class="grp"><h3>' + ic('warn') + '能力边界</h3><div style="font-size:12px;color:var(--ink2);line-height:1.65">' +
        '企微渠道是<b>只读采集</b>：群消息只能看，不能从本系统发出。要回消息得跳转企微客户端。</div></div>' +
      '</aside></div>' +
    '<div class="compose"><div class="cmp-tabs"><button class="t on">' + ic('wecom') + '企业微信</button>' +
      '<span class="who">' + ic('warn') + '群消息不支持从本系统直接发送 → 用「打开企微客户端」跳转</span></div>' +
      '<div class="cmp-ft"><button class="btn sm" data-act="pick" data-v="fetchWeComGroupThread">' + ic('clock') + '重拉群会话</button>' +
      '<button class="btn sm">' + ic('link') + '打开企微客户端</button>' +
      '<span class="hint">POST /api/v1/wecom/groups/{sourceConversationId}/name-refresh</span>' +
      '<span class="r"><button class="btn sm" data-act="pick" data-v="retryWeComGroupTopicGeneration">重试群话题生成</button>' +
      '<button class="btn sm pri" data-act="pick" data-v="syncWeComDirectoryProfiles">同步通讯录</button></span></div></div></div>';
};

/* ================= 联系人 ================= */
PAGE.contacts = function () {
  call('fetchContacts', { page: 1, size: 20 }, '联系人 · 列表');
  return '<div style="display:flex;flex-direction:column;gap:16px">' +
    '<div class="crumbs"><span>统一消息中心</span>' + ic('chev') + '<code>#/contacts</code>' +
    '<span style="margin-left:auto">真实应用里联系人<b>不是独立路由</b>，由 <code>components/ContactsPage.tsx</code> 渲染在顶栏抽屉里</span></div>' +
    '<div class="pghd"><div><h1>联系人</h1><p>共 ' + window.MC_CONTACTS.length + ' 位，跨 4 个渠道；同一贸易公司下的国内与海外联系人都挂在各自名下。</p></div>' +
    '<div class="act"><button class="btn" data-act="pick" data-v="mergeContacts">' + ic('flow') + '合并联系人</button>' +
    '<button class="btn pri" data-act="pick" data-v="fetchContacts">' + ic('search') + '跨渠道搜索</button></div></div>' +
    '<section class="panel"><table class="tbl"><thead><tr><th>名称</th><th>公司 / 地区</th><th>渠道</th><th>身份数</th><th>负责人</th><th>最后消息</th><th class="r">未读</th></tr></thead><tbody>' +
    window.MC_CONTACTS.map(function (c) {
      return '<tr data-go="#/conversations/contact/' + c.id + '" style="cursor:pointer">' +
        '<td><div style="display:flex;align-items:center;gap:9px"><span class="av ' + c.tone + '" style="width:26px;height:26px;border-radius:8px;font-size:11px">' + esc(c.initials) + '</span>' +
        '<div><b style="font-size:12.5px">' + esc(c.displayName) + '</b><div style="font-size:11px;color:var(--ink3)">' + esc(c.remark) + '</div></div></div></td>' +
        '<td>' + esc(c.company) + '<div style="font-size:11px;color:var(--ink3)">' + esc(c.region) + '</div></td>' +
        '<td><span style="display:flex;gap:4px">' + c.channelTypes.map(CHI).join('') + '</span></td>' +
        '<td class="mono">' + c.identities.length + '</td><td>' + esc(c.owner) + '</td>' +
        '<td class="mono">' + md(c.lastMessageAt) + ' ' + hm(c.lastMessageAt) + '</td>' +
        '<td class="r">' + (c.unreadCount ? '<span class="pill-n" style="display:inline-grid">' + c.unreadCount + '</span>' : '<span style="color:var(--ink3)">—</span>') + '</td></tr>';
    }).join('') + '</tbody></table></section>' +
    '<div class="cards">' +
    [['合并联系人', 'mergeContacts', '同一公司在多个渠道下重复建档时合并 —— 会话与身份一起搬家'],
     ['拆分身份', 'splitContact', '把某个渠道身份拆出去独立成联系人（例如代收同事的邮件）'],
     ['更新备注', 'updateContactRemark', '备注是「列表显示名」的来源；详情页的名称与备注是两个独立字段'],
     ['联系人记忆', 'fetchContactMemory', 'AI 侧的联系人摘要 + 信号，纯元数据表，可安全进 brief']].map(function (x) {
      return '<div class="card" data-act="pick" data-v="' + x[1] + '" style="cursor:pointer"><div class="ttl">' + ic('flow') + x[0] + '</div>' +
        '<div class="dsc">' + x[2] + '</div><div class="rt"><span class="tag gray mono" style="font-family:var(--mono);font-size:11px">' + x[1] + '</span>' +
        '<span class="sp" style="color:var(--ink3)">' + ic('chev') + '</span></div></div>';
    }).join('') + '</div></div>';
};

/* ================= 待办日历 ================= */
PAGE.todos = function () {
  call('fetchTodos', null, '待办日历 · 列表');
  call('createTodoApi', { date: '2026-09-29' }, '待办日历 · 新建');
  var days = ['2026-09-29', '2026-09-30', '2026-10-05', '2026-10-10'];
  var cells = days.map(function (d) {
    var list = window.MC_TODOS.filter(function (t) { return t.date === d; });
    return '<section class="panel" style="flex:1;min-width:0"><div class="hd"><h2>' + d.slice(5) + '</h2><span class="cnt">' + list.length + '</span></div>' +
      '<div class="todo">' + (list.length ? list.map(function (t) {
        return '<div class="td ' + (t.done ? 'done' : '') + '" data-act="todo" data-v="' + t.id + '">' +
          '<span class="box">' + ic('ok') + '</span><span class="txt">' + esc(t.title) + '</span>' +
          '<span class="due ' + (t.hot ? 'hot' : '') + '">' + t.time + '</span></div>';
      }).join('') : '<div style="padding:14px 12px;font-size:12.5px;color:var(--ink3)">没有待办</div>') + '</div></section>';
  }).join('');
  return '<div style="display:flex;flex-direction:column;gap:16px">' +
    '<div class="crumbs"><span>统一消息中心</span>' + ic('chev') + '<code>#/todo-calendar</code>' +
    '<span style="margin-left:auto">渲染自 <code>pages/TodoCalendarPage.tsx</code> · 点勾选框会真的发一条 PATCH</span></div>' +
    '<div class="pghd"><div><h1>待办日历</h1><p>今天 4 件，其中 2 件有逾期风险；10/10 是 10/12 船期的截港日。</p></div>' +
    '<div class="act"><button class="btn" data-act="pick" data-v="fetchTodos">' + ic('clock') + '重新拉取</button>' +
    '<button class="btn pri" data-act="pick" data-v="createTodoApi">' + ic('plus') + '新建待办</button></div></div>' +
    '<div style="display:flex;gap:16px;align-items:flex-start">' + cells + '</div></div>';
};

/* ================= 发送 ================= */
PAGE.send = function () {
  call('fetchContacts', { page: 1, size: 50 }, '发送 · 选联系人');
  call('fetchTemplates', null, '发送 · 模板清单');
  call('fetchChannelCapabilities', null, '发送 · 渠道能力');
  var picked = window.MC_CONTACTS[0];
  return '<div style="display:flex;flex-direction:column;gap:16px">' +
    '<div class="crumbs"><span>统一消息中心</span>' + ic('chev') + '<code>#/send</code>' +
    '<span style="margin-left:auto">渲染自 <code>pages/SendPage.tsx</code> · 复用 <code>components/SendForm.tsx</code></span></div>' +
    '<div class="pghd"><div><h1>发送</h1><p>人工入口可发任意合法地址；<b>AI 入口只接受 contactRef</b> —— 收件人不是模型的参数，模型编不出地址。</p></div>' +
    '<div class="act"><button class="btn" data-act="pick" data-v="sendEmail">' + ic('send') + '试发邮件</button>' +
    '<button class="btn pri" data-act="pick" data-v="sendChatApp">' + ic('send') + '试发模板</button></div></div>' +
    '<div style="display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.15fr);gap:16px;align-items:start">' +
    '<section class="panel" style="padding-bottom:14px"><div class="hd"><h2>选择联系人</h2><span class="cnt">' + window.MC_CONTACTS.length + '</span></div>' +
      '<div class="todo">' + window.MC_CONTACTS.map(function (c) {
        return '<div class="td" data-go="#/conversations/contact/' + c.id + '">' +
          '<span class="av ' + c.tone + '" style="width:26px;height:26px;border-radius:8px;font-size:11px">' + esc(c.initials) + '</span>' +
          '<span class="txt">' + esc(c.displayName) + ' · ' + esc(c.remark) + '</span>' +
          '<span class="due">' + c.channelTypes.length + ' 渠道</span></div>';
      }).join('') + '</div></section>' +
    '<section class="panel" style="padding-bottom:14px"><div class="hd"><h2>发送表单 <span>SendForm 组件</span></h2>' +
      '<span class="cnt">' + esc(picked.displayName) + '</span></div>' +
      '<div style="padding:0 20px">' + CH_COMPOSE.email(picked) + '</div></section>' +
    '</div></div>';
};

/* ================= 管理端 4 页 ================= */
PAGE.admin = function () {
  call('fetchAdminWhatsAppOverview', null, '管理端 · 总览');
  call('fetchAdminUsers', { page: 0, size: 20 }, '管理端 · 用户');
  call('fetchAdminCams', null, '管理端 · CAMS 范围');
  call('fetchAdminTemplates', { accountId: 'a4' }, '管理端 · 模板');
  return shell('管理员工作台', '#/admin', 'pages/AdminHomePage.tsx',
    '平台接入 5 项，1 项告警（电话录音网关）；CAMS 2 个范围全部 active。',
    [['pick', 'syncAdminWhatsAppAccounts', 'bolt', '同步账号'], ['pick', 'fetchAdminWhatsAppOverview', 'clock', '重新拉取']],
    '<section class="panel"><div class="stats">' +
      [['平台', 5, '1 告警'], ['WhatsApp 账号', 1, 'active'], ['CAMS 范围', 2, '2 active'],
       ['模板', window.MC_TEMPLATES.length, '3 待审'], ['24h 失败', 5, '群发']].map(function (x) {
        return '<div class="stat"><div class="lab">' + x[0] + '</div><div class="v"><b>' + x[1] + '</b><em>' + x[2] + '</em></div></div>';
      }).join('') + '</div></section>' +
    '<div class="cards">' + window.MC_ADMIN.platforms.map(function (p) {
      return '<div class="card"><div class="ttl">' + ic('layers') + esc(p.name) + '</div><div class="dsc">' + esc(p.kind) + '</div>' +
        '<div class="rt">' + (p.state === 'active' ? '<span class="tag good">active</span>' : p.state === 'warning' ? '<span class="tag w8">warning</span>' : '<span class="tag gray">inactive</span>') +
        '<span class="sp mono" style="font-size:11px;color:var(--ink3)">' + p.accounts + ' 个账号</span></div></div>';
    }).join('') + '</div>' +
    '<section class="panel"><div class="hd"><h2>入口</h2><span class="cnt">4</span></div><div class="cards" style="padding:0 20px 20px">' +
    [['平台接入', '#/admin/platforms', 'layers'], ['WhatsApp 账号', '#/admin/whatsapp/accounts', 'wa'],
     ['模板审批', '#/admin/whatsapp/template-approvals', 'flow'], ['用户管理', '#/settings/users', 'user']].map(function (x) {
      return '<div class="card" data-go="' + x[1] + '" style="cursor:pointer"><div class="ttl">' + ic(x[2]) + x[0] + '</div>' +
        '<div class="dsc mono" style="font-family:var(--mono);font-size:11px">' + x[1] + '</div>' +
        '<div class="rt"><span class="sp" style="color:var(--ink3)">' + ic('chev') + '</span></div></div>';
    }).join('') + '</div></section>');
};

PAGE.adminPf = function () {
  call('fetchChannelCapabilities', null, '平台接入 · 能力矩阵');
  var rows = [['邮件', 'IMAP / SMTP', 1, 1, 1, 'active'], ['企业微信', '自建应用 + 通讯录', 0, 1, 1, 'active'],
    ['WhatsApp', 'Cloud API（WABA）', 1, 1, 1, 'active'], ['电话', 'SIP 中继 + ASR', 0, 1, 1, 'warning'],
    ['ChatApp', '模板消息投递', 1, 0, 1, 'inactive']];
  return shell('平台接入', '#/admin/platforms', 'pages/AdminPlatformsPage.tsx',
    '5 个平台的能力矩阵。注意<b>企业微信是只读的</b>（不能发消息），电话只支持录音上传后转录，没有「打电话」接口。',
    [['pick', 'fetchChannelCapabilities', 'clock', '重新拉取']],
    '<section class="panel"><table class="tbl"><thead><tr><th>平台</th><th>接入方式</th><th>发消息</th><th>收消息</th><th>媒体</th><th>状态</th></tr></thead><tbody>' +
    rows.map(function (r) {
      return '<tr><td><b style="font-size:12.5px">' + r[0] + '</b></td><td class="mono">' + r[1] + '</td>' +
        '<td>' + (r[2] ? '<span class="tag good">支持</span>' : '<span class="tag gray">只读</span>') + '</td>' +
        '<td>' + (r[3] ? '<span class="tag good">支持</span>' : '<span class="tag gray">不支持</span>') + '</td>' +
        '<td>' + (r[4] ? '<span class="tag good">支持</span>' : '<span class="tag gray">不支持</span>') + '</td>' +
        '<td><span class="tag ' + (r[5] === 'active' ? 'good' : r[5] === 'warning' ? 'w8' : 'gray') + '">' + r[5] + '</span></td></tr>';
    }).join('') + '</tbody></table></section>');
};

PAGE.adminWa = function () {
  call('fetchAdminWhatsAppAccounts', null, 'WhatsApp 账号 · 列表');
  call('fetchAdminScopedWhatsAppAccounts', { scopeId: 'cams1' }, 'WhatsApp 账号 · 范围内账号');
  call('fetchAdminWhatsAppCallbacks', { scopeId: 'cams1' }, 'WhatsApp 账号 · 回调配置');
  return shell('WhatsApp 账号（管理端）', '#/admin/whatsapp/accounts', 'pages/AdminWhatsAppAccountsPage.tsx',
    '1 个 Cloud API 账号；CAMS 2 个范围共 3 个号码；账号可在范围之间分配 / 回收 / 转移。',
    [['pick', 'syncAdminWhatsAppAccounts', 'bolt', '同步账号'], ['pick', 'assignAdminScopedWhatsAppAccount', 'flow', '分配账号（试调用）']],
    '<section class="panel"><table class="tbl"><thead><tr><th>accountId</th><th>号码</th><th>归属人</th><th>质量</th><th>状态</th><th class="r">操作</th></tr></thead><tbody>' +
    '<tr><td class="mono">a4</td><td class="mono">+86 755 8899 0120</td><td>林一鸣</td><td><span class="tag good">GREEN</span></td><td><span class="tag good">active</span></td>' +
    '<td class="r"><button class="btn sm" data-act="pick" data-v="reclaimAdminScopedWhatsAppAccount">回收</button> ' +
    '<button class="btn sm" data-act="pick" data-v="transferAdminScopedWhatsAppAccount">转移</button></td></tr>' +
    '</tbody></table></section>' +
    '<section class="panel"><div class="hd"><h2>CAMS 范围</h2><span class="cnt">2</span></div>' +
    '<table class="tbl"><thead><tr><th>空间</th><th>区域</th><th>号码数</th><th>素材数</th><th>状态</th><th class="r">操作</th></tr></thead><tbody>' +
    window.MC_WA.cams.map(function (c) {
      return '<tr><td><b style="font-size:12.5px">' + esc(c.space) + '</b></td><td class="mono">' + c.region + '</td>' +
        '<td class="mono">' + c.phones + '</td><td class="mono">' + c.media + '</td><td><span class="tag good">' + c.state + '</span></td>' +
        '<td class="r"><button class="btn sm" data-act="pick" data-v="testAdminCams">测试连通</button> ' +
        '<button class="btn sm" data-act="pick" data-v="syncAdminCams">同步</button> ' +
        '<button class="btn sm" data-act="pick" data-v="fetchAdminScopedAssignmentHistory">分配历史</button></td></tr>';
    }).join('') + '</tbody></table></section>' +
    '<section class="panel"><div class="hd"><h2>回调配置</h2><span class="cnt">cams1</span></div>' +
    '<table class="tbl"><thead><tr><th>对象</th><th>回调地址</th><th>校验</th><th class="r">操作</th></tr></thead><tbody>' +
    '<tr><td>号码级回调</td><td class="mono">https://api.huanyu-logistics.com/wa/phone</td><td><span class="tag good">已验证</span></td>' +
    '<td class="r"><button class="btn sm" data-act="pick" data-v="updateAdminWhatsAppPhoneCallback">修改</button></td></tr>' +
    '<tr><td>账号级回调</td><td class="mono">https://api.huanyu-logistics.com/wa/account</td><td><span class="tag good">已验证</span></td>' +
    '<td class="r"><button class="btn sm" data-act="pick" data-v="updateAdminWhatsAppAccountCallback">修改</button></td></tr>' +
    '</tbody></table></section>');
};

PAGE.adminTpl = function () {
  call('fetchTemplateChangeRequestsForReview', { page: 1, size: 20 }, '模板审批 · 待审列表');
  call('fetchAdminTemplate', { accountId: 'a4', templateCode: 'arrival_notice_3d' }, '模板审批 · 模板详情');
  var reqs = [
    { id: 'rr1', name: '到港提醒（ETA-3）', who: '林一鸣', what: '正文第 2 个变量由 (shipment) 改为 (container_no)', at: '2026-09-29T09:12:00+08:00', state: 'pending' },
    { id: 'rr2', name: 'Q4 运价锁定通知', who: '李伟', what: '新增模板，类目 MARKETING', at: '2026-09-28T17:40:00+08:00', state: 'pending' },
    { id: 'rr3', name: '账单与对账提醒', who: '孙倩', what: '正文补充账期与到期日', at: '2026-09-27T11:02:00+08:00', state: 'rejected' },
  ];
  return shell('模板审批', '#/admin/whatsapp/template-approvals', 'pages/AdminWhatsAppTemplateApprovalsPage.tsx',
    '3 条变更申请，2 条待审。模板变更改的是<b>已上线</b>的 WhatsApp 模板，改完要重新过 Meta 审核。',
    [['pick', 'fetchTemplateChangeRequestsForReview', 'clock', '重新拉取']],
    '<section class="panel"><table class="tbl"><thead><tr><th>模板</th><th>申请人</th><th>变更内容</th><th>提交时间</th><th>状态</th><th class="r">操作</th></tr></thead><tbody>' +
    reqs.map(function (r) {
      return '<tr><td><b style="font-size:12.5px">' + esc(r.name) + '</b></td><td>' + esc(r.who) + '</td>' +
        '<td style="font-size:12px;color:var(--ink2)">' + esc(r.what) + '</td>' +
        '<td class="mono">' + md(r.at) + ' ' + hm(r.at) + '</td>' +
        '<td>' + (r.state === 'pending' ? '<span class="tag w4">待审</span>' : '<span class="tag w8">已驳回</span>') + '</td>' +
        '<td class="r">' + (r.state === 'pending'
          ? '<button class="btn sm" data-act="pick" data-v="approveTemplateChangeRequest">通过</button> ' +
            '<button class="btn sm" data-act="pick" data-v="rejectTemplateChangeRequest">驳回</button>'
          : '<button class="btn sm" data-act="pick" data-v="retryTemplateChangeRequest">重新提交</button>') + '</td></tr>';
    }).join('') + '</tbody></table></section>' +
    '<section class="panel"><div class="hd"><h2>变更前后对照</h2><span class="cnt">rr1</span></div>' +
    '<div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;padding:0 20px 20px">' +
    '<div><div class="sec-t2" style="margin-top:0">改前（线上版本）</div><div class="code">Hi {{1}}, this is a reminder that shipment {{2}}\nis expected to arrive at {{3}} on {{4}}.</div></div>' +
    '<div><div class="sec-t2" style="margin-top:0">改后（待审版本）</div><div class="code">Hi {{1}}, this is a reminder that container {{2}}\nis expected to arrive at {{3}} on {{4}}.</div></div>' +
    '</div></section>');
};

})();
