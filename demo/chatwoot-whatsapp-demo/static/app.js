const $ = (id) => document.getElementById(id);

function log(title, data) {
  const now = new Date().toLocaleTimeString();
  const payload = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
  $('logOutput').textContent = `[${now}] ${title}\n${payload}\n\n` + $('logOutput').textContent;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || response.statusText);
  return data;
}

function readConfigForm() {
  return {
    mode: $('mode').value,
    chatwoot_url: $('chatwootUrl').value.trim(),
    account_id: $('accountId').value ? Number($('accountId').value) : null,
    inbox_id: $('inboxId').value ? Number($('inboxId').value) : null,
    api_token: $('apiToken').value,
    webhook_secret: $('webhookSecret').value
  };
}

function renderConfig(config) {
  $('mode').value = config.mode || 'mock';
  $('chatwootUrl').value = config.chatwoot_url || '';
  $('accountId').value = config.account_id || '';
  $('inboxId').value = config.inbox_id || '';
  $('modePill').textContent = config.mode || 'mock';
  $('modePill').className = `status-pill ${config.mode === 'real' ? 'real' : ''}`;
  $('configStatus').textContent = JSON.stringify(config, null, 2);
}

async function loadStatus() {
  const data = await api('/api/status');
  renderConfig(data.config);
  return data;
}

async function saveConfig() {
  const data = await api('/api/config', {
    method: 'POST',
    body: JSON.stringify(readConfigForm())
  });
  renderConfig(data.config);
  log('配置已保存', data.config);
}

async function sendText() {
  const data = await api('/api/send-text', {
    method: 'POST',
    body: JSON.stringify({
      phone: $('phone').value.trim(),
      name: $('name').value.trim(),
      content: $('textContent').value.trim()
    })
  });
  log('文本发送完成', data);
  await loadMessages();
}

async function sendTemplate() {
  const parameters = $('templateParams').value.split(',').map((item) => item.trim()).filter(Boolean);
  const data = await api('/api/send-template', {
    method: 'POST',
    body: JSON.stringify({
      phone: $('phone').value.trim(),
      name: $('name').value.trim(),
      template_name: $('templateName').value.trim(),
      language: $('templateLang').value.trim(),
      parameters
    })
  });
  log('模板发送完成', data);
  await loadMessages();
}

async function sendWebhook() {
  let payload;
  try {
    payload = JSON.parse($('webhookJson').value);
  } catch (error) {
    log('Webhook JSON 格式错误', error.message);
    return;
  }
  const data = await api('/webhooks/chatwoot', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
  log('入站事件已接收', data);
  await loadMessages();
  await loadWebhooks();
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
        <span>${escapeHtml(item.phone || '')}</span>
        <span>${escapeHtml(item.direction)} · ${escapeHtml(item.message_type)}</span>
      </div>
      <p>${escapeHtml(item.content)}</p>
      <time>${escapeHtml(item.created_at || '')}</time>
    `;
    list.appendChild(node);
  }
}

async function loadWebhooks() {
  const data = await api('/api/webhooks');
  const list = $('eventList');
  list.innerHTML = '';
  if (!data.items.length) {
    list.innerHTML = '<div class="empty">暂无 webhook 事件</div>';
    return;
  }
  for (const item of data.items) {
    const node = document.createElement('article');
    node.className = 'event';
    node.innerHTML = `
      <div class="message-meta"><span>#${item.id}</span><span>${escapeHtml(item.event_name)}</span></div>
      <code>${escapeHtml(item.payload_json)}</code>
    `;
    list.appendChild(node);
  }
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
  $('sendWebhookBtn').addEventListener('click', () => sendWebhook().catch((error) => log('入站失败', error.message)));
  $('loadMessagesBtn').addEventListener('click', () => loadMessages().catch((error) => log('刷新消息失败', error.message)));
  $('loadWebhooksBtn').addEventListener('click', () => loadWebhooks().catch((error) => log('刷新事件失败', error.message)));
  $('clearLogBtn').addEventListener('click', () => { $('logOutput').textContent = ''; });
  $('searchBox').addEventListener('input', () => loadMessages().catch((error) => log('搜索失败', error.message)));
  await loadStatus();
  await loadMessages();
  await loadWebhooks();
}

bind().catch((error) => log('初始化失败', error.message));