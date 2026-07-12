const $ = (id) => document.getElementById(id);

function log(title, data) {
  const now = new Date().toLocaleTimeString();
  const payload = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
  $('logOutput').textContent = `[${now}] ${title}\n${payload}\n\n` + $('logOutput').textContent;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || response.statusText);
  return data;
}

function readConfig() {
  return {
    mode: $('mode').value,
    graph_version: $('graphVersion').value.trim() || 'v20.0',
    phone_number_id: $('phoneNumberId').value.trim(),
    business_account_id: $('businessAccountId').value.trim(),
    access_token: $('accessToken').value,
    verify_token: $('verifyToken').value
  };
}

function renderConfig(config) {
  $('mode').value = config.mode || 'dry_run';
  $('graphVersion').value = config.graph_version || 'v20.0';
  $('phoneNumberId').value = config.phone_number_id || '';
  $('businessAccountId').value = config.business_account_id || '';
  $('modePill').textContent = config.mode || 'dry_run';
  $('modePill').className = `status-pill ${config.mode === 'real' ? 'real' : ''}`;
  $('configStatus').textContent = JSON.stringify(config, null, 2);
}

async function loadStatus() {
  const data = await api('/api/status');
  renderConfig(data.config);
  return data;
}

async function saveConfig() {
  const data = await api('/api/config', { method: 'POST', body: JSON.stringify(readConfig()) });
  renderConfig(data.config);
  log('配置已保存', data.config);
}

async function sendText() {
  const data = await api('/api/send-text', {
    method: 'POST',
    body: JSON.stringify({ to: $('to').value.trim(), name: $('name').value.trim(), body: $('textBody').value.trim() })
  });
  log('文本发送结果', data);
  await loadMessages();
}

async function sendTemplate() {
  const params = $('templateParams').value.split(',').map((item) => item.trim()).filter(Boolean);
  const data = await api('/api/send-template', {
    method: 'POST',
    body: JSON.stringify({
      to: $('to').value.trim(),
      name: $('name').value.trim(),
      template_name: $('templateName').value.trim(),
      language: $('templateLang').value.trim(),
      body_parameters: params
    })
  });
  log('模板发送结果', data);
  await loadMessages();
}

async function sendMedia() {
  const data = await api('/api/send-media', {
    method: 'POST',
    body: JSON.stringify({
      to: $('to').value.trim(),
      name: $('name').value.trim(),
      media_type: $('mediaType').value,
      link: $('mediaLink').value.trim(),
      caption: $('caption').value.trim()
    })
  });
  log('媒体发送结果', data);
  await loadMessages();
}

async function sendWebhook() {
  let payload;
  try { payload = JSON.parse($('webhookJson').value); }
  catch (error) { log('Webhook JSON 格式错误', error.message); return; }
  const data = await api('/webhook/whatsapp', { method: 'POST', body: JSON.stringify(payload) });
  log('Webhook 已接收', data);
  await loadMessages();
  await loadEvents();
}

async function loadMessages() {
  const q = encodeURIComponent($('searchBox').value.trim());
  const data = await api(`/api/messages?q=${q}`);
  const list = $('messageList');
  list.innerHTML = '';
  if (!data.items.length) {
    list.innerHTML = '<div class="empty">暂无消息记录</div>';
    return;
  }
  for (const item of data.items) {
    const node = document.createElement('article');
    node.className = `message ${item.direction}`;
    node.innerHTML = `
      <div class="message-meta">
        <span>${escapeHtml(item.contact_name || 'Unknown')}</span>
        <span>${escapeHtml(item.wa_id || '')}</span>
        <span>${escapeHtml(item.direction)} · ${escapeHtml(item.message_type)}</span>
      </div>
      <p>${escapeHtml(item.content)}</p>
      <time>${escapeHtml(item.created_at || '')}</time>
    `;
    list.appendChild(node);
  }
}

async function loadEvents() {
  const [events, statuses] = await Promise.all([api('/api/events'), api('/api/statuses')]);
  const list = $('eventList');
  list.innerHTML = '';
  for (const status of statuses.items) {
    const node = document.createElement('article');
    node.className = 'event status';
    node.innerHTML = `<div class="message-meta"><span>status</span><span>${escapeHtml(status.status)}</span><span>${escapeHtml(status.recipient_id)}</span></div><code>${escapeHtml(status.payload_json)}</code>`;
    list.appendChild(node);
  }
  for (const event of events.items) {
    const node = document.createElement('article');
    node.className = 'event';
    node.innerHTML = `<div class="message-meta"><span>webhook #${event.id}</span><span>${escapeHtml(event.created_at)}</span></div><code>${escapeHtml(event.payload_json)}</code>`;
    list.appendChild(node);
  }
  if (!list.children.length) list.innerHTML = '<div class="empty">暂无事件</div>';
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

async function bind() {
  $('refreshBtn').addEventListener('click', () => loadStatus().then((data) => log('状态已刷新', data.config)).catch((error) => log('刷新失败', error.message)));
  $('saveConfigBtn').addEventListener('click', () => saveConfig().catch((error) => log('保存失败', error.message)));
  $('sendTextBtn').addEventListener('click', () => sendText().catch((error) => log('文本发送失败', error.message)));
  $('sendTemplateBtn').addEventListener('click', () => sendTemplate().catch((error) => log('模板发送失败', error.message)));
  $('sendMediaBtn').addEventListener('click', () => sendMedia().catch((error) => log('媒体发送失败', error.message)));
  $('sendWebhookBtn').addEventListener('click', () => sendWebhook().catch((error) => log('Webhook 失败', error.message)));
  $('loadEventsBtn').addEventListener('click', () => loadEvents().catch((error) => log('刷新事件失败', error.message)));
  $('clearLogBtn').addEventListener('click', () => { $('logOutput').textContent = ''; });
  $('searchBox').addEventListener('input', () => loadMessages().catch((error) => log('搜索失败', error.message)));
  await loadStatus();
  await loadMessages();
  await loadEvents();
}

bind().catch((error) => log('初始化失败', error.message));