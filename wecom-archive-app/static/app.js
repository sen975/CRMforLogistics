const $ = (selector) => document.querySelector(selector);

const state = {
  messages: [],
};

function log(message, data) {
  const line = `[${new Date().toLocaleTimeString()}] ${message}`;
  const extra = data ? `\n${JSON.stringify(data, null, 2)}` : "";
  $("#log").textContent = `${line}${extra}\n\n${$("#log").textContent}`;
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  const payload = await res.json().catch(() => ({}));
  if (!res.ok || payload.ok === false) {
    throw new Error(payload.error || `HTTP ${res.status}`);
  }
  return payload;
}

function formValues(form) {
  const data = {};
  new FormData(form).forEach((value, key) => {
    data[key] = String(value).trim();
  });
  return data;
}

function fillConfig(status) {
  const form = $("#config-form");
  const paths = status.paths || {};
  const network = status.network || {};
  const masked = status.masked || {};
  form.corp_id.value = masked.corp_id || "";
  form.secret.value = "";
  form.private_key_path.value = paths.private_key_path || "";
  form.sdk_lib_path.value = paths.sdk_lib_path || "";
  form.db_path.value = paths.db_path || "";
  form.media_dir.value = paths.media_dir || "";
  form.proxy.value = network.proxy || "";
  form.timeout.value = network.timeout || 5;
  form.passwd.value = "";
}

function setStatus(status) {
  const dot = $("#config-dot");
  dot.className = "dot";
  if (status.configured) {
    dot.classList.add("ok");
    $("#config-text").textContent = "配置可用";
  } else {
    dot.classList.add("bad");
    const failed = Object.entries(status.checks || {})
      .filter(([, item]) => !item.ok)
      .map(([name]) => name)
      .join(", ");
    $("#config-text").textContent = failed ? `待配置：${failed}` : "配置不完整";
  }
}

async function refreshStatus() {
  const payload = await api("/api/config/status");
  setStatus(payload.status);
  fillConfig(payload.status);
  log("配置状态已刷新", payload.status.checks);
}

async function saveConfig(event) {
  event.preventDefault();
  const btn = event.submitter;
  btn.disabled = true;
  try {
    const payload = await api("/api/config", {
      method: "POST",
      body: JSON.stringify(formValues($("#config-form"))),
    });
    setStatus(payload.status);
    fillConfig(payload.status);
    log("配置已保存", payload.status.checks);
  } catch (err) {
    log("配置保存失败", { error: err.message });
  } finally {
    btn.disabled = false;
  }
}

async function syncMessages() {
  const btn = $("#sync-btn");
  btn.disabled = true;
  $("#sync-result").textContent = "同步中...";
  try {
    const seq = $("#sync-seq").value.trim();
    const payload = await api("/api/sync", {
      method: "POST",
      body: JSON.stringify({
        limit: $("#sync-limit").value || 1000,
        seq: seq === "" ? null : Number(seq),
      }),
    });
    $("#sync-result").textContent = `拉取 ${payload.result.fetched} 条，入库 ${payload.result.stored} 条，当前 seq ${payload.result.next_seq}`;
    log("同步完成", payload.result);
    await searchMessages();
  } catch (err) {
    $("#sync-result").textContent = err.message;
    log("同步失败", { error: err.message });
  } finally {
    btn.disabled = false;
  }
}

function queryString(params) {
  const sp = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && String(value).trim() !== "") {
      sp.set(key, value);
    }
  });
  return sp.toString();
}

async function searchMessages() {
  const params = queryString({
    keyword: $("#filter-keyword").value,
    sender: $("#filter-sender").value,
    roomid: $("#filter-roomid").value,
    msgtype: $("#filter-msgtype").value,
    limit: $("#filter-limit").value || 100,
  });
  try {
    const payload = await api(`/api/messages?${params}`);
    state.messages = payload.items || [];
    renderMessages();
    log("检索完成", { count: payload.count, last_seq: payload.last_seq });
  } catch (err) {
    log("检索失败", { error: err.message });
  }
}

function renderMessages() {
  const body = $("#message-body");
  $("#message-count").textContent = `${state.messages.length} 条`;
  if (!state.messages.length) {
    body.innerHTML = '<tr><td colspan="6" class="empty">暂无数据</td></tr>';
    return;
  }
  body.innerHTML = "";
  state.messages.forEach((msg, index) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${formatTime(msg.msgtime)}<br><span class="subtle">seq ${escapeHtml(msg.seq)}</span></td>
      <td><span class="pill">${escapeHtml(msg.msgtype || "-")}</span></td>
      <td>${escapeHtml(msg.sender || "-")}</td>
      <td>${escapeHtml(msg.roomid || (msg.tolist || []).join(", ") || "-")}</td>
      <td><div class="content-cell">${escapeHtml(msg.content || "")}</div></td>
      <td>${mediaActions(msg, index)}</td>
    `;
    body.appendChild(tr);
  });
}

function mediaActions(msg, index) {
  const refs = msg.media_refs || [];
  if (!refs.length) return '<span class="subtle">无媒体</span>';
  return `<div class="media-actions">${refs
    .map((ref, refIndex) => {
      const label = ref.media_type === "voice" ? "下载原语音" : `下载${typeName(ref.media_type)}`;
      return `<button type="button" data-message-index="${index}" data-ref-index="${refIndex}">${label}</button>`;
    })
    .join("")}</div>`;
}

async function downloadMedia(button) {
  const msg = state.messages[Number(button.dataset.messageIndex)];
  const ref = (msg.media_refs || [])[Number(button.dataset.refIndex)];
  if (!msg || !ref) return;
  button.disabled = true;
  try {
    const payload = await api("/api/media/download", {
      method: "POST",
      body: JSON.stringify({
        msgid: msg.msgid,
        sdkfileid: ref.sdkfileid,
        media_type: ref.media_type,
        file_name: ref.file_name,
      }),
    });
    const url = payload.media.url;
    if (url) {
      button.outerHTML = `<a href="${escapeAttr(url)}" download>打开原文件</a>`;
    }
    log("媒体下载完成", payload.media);
  } catch (err) {
    button.disabled = false;
    log("媒体下载失败", { error: err.message });
  }
}

function typeName(type) {
  return {
    image: "图片",
    voice: "原语音",
    file: "文件",
    video: "视频",
    emotion: "表情",
  }[type] || "媒体";
}

function formatTime(value) {
  const n = Number(value || 0);
  if (!n) return "-";
  const ms = n > 100000000000 ? n : n * 1000;
  return new Date(ms).toLocaleString();
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function escapeAttr(value) {
  return escapeHtml(value).replaceAll("`", "&#96;");
}

function bind() {
  $("#config-form").addEventListener("submit", saveConfig);
  $("#refresh-status").addEventListener("click", refreshStatus);
  $("#sync-btn").addEventListener("click", syncMessages);
  $("#search-btn").addEventListener("click", searchMessages);
  $("#clear-log").addEventListener("click", () => {
    $("#log").textContent = "";
  });
  $("#message-body").addEventListener("click", (event) => {
    const button = event.target.closest("button[data-message-index]");
    if (button) downloadMedia(button);
  });
  ["filter-keyword", "filter-sender", "filter-roomid"].forEach((id) => {
    $(`#${id}`).addEventListener("keydown", (event) => {
      if (event.key === "Enter") searchMessages();
    });
  });
}

bind();
refreshStatus().catch((err) => log("配置状态读取失败", { error: err.message }));
searchMessages();
