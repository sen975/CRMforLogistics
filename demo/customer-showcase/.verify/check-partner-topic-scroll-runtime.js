const fs = require("fs");
const http = require("http");
const path = require("path");

const port = Number(process.env.CDP_PORT || 9333);
const root = path.resolve(__dirname, "..");
const targetUrl = `file:///${root.replace(/\\/g, "/")}/index.html?page=partners&partner=pacific-star&partnerDetail=1&partnerTab=contacts&partnerContact=Marco%20Ruiz`;

function requestJson(route) {
  return new Promise((resolve, reject) => {
    http.get({ hostname: "127.0.0.1", port, path: route }, (res) => {
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => { body += chunk; });
      res.on("end", () => {
        try {
          resolve(JSON.parse(body));
        } catch (error) {
          reject(new Error(`Invalid JSON from ${route}: ${body.slice(0, 120)}`));
        }
      });
    }).on("error", reject);
  });
}

function sendWsFrame(socket, payload) {
  const data = Buffer.from(JSON.stringify(payload));
  let header;
  if (data.length < 126) {
    header = Buffer.alloc(6);
    header[0] = 0x81;
    header[1] = 0x80 | data.length;
    header.writeUInt32BE(0, 2);
  } else {
    header = Buffer.alloc(8);
    header[0] = 0x81;
    header[1] = 0x80 | 126;
    header.writeUInt16BE(data.length, 2);
    header.writeUInt32BE(0, 4);
  }
  socket.write(Buffer.concat([header, data]));
}

function connectWebSocket(wsUrl) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(wsUrl);
    const key = Buffer.from("partner-topic-scroll-check").toString("base64");
    const socket = require("net").connect(Number(parsed.port), parsed.hostname, () => {
      socket.write([
        `GET ${parsed.pathname}${parsed.search} HTTP/1.1`,
        `Host: ${parsed.host}`,
        "Upgrade: websocket",
        "Connection: Upgrade",
        `Sec-WebSocket-Key: ${key}`,
        "Sec-WebSocket-Version: 13",
        "",
        ""
      ].join("\r\n"));
    });
    let handshake = "";
    socket.on("data", function onHandshake(chunk) {
      handshake += chunk.toString("latin1");
      const splitAt = handshake.indexOf("\r\n\r\n");
      if (splitAt === -1) return;
      socket.removeListener("data", onHandshake);
      if (!handshake.startsWith("HTTP/1.1 101")) {
        reject(new Error(`WebSocket handshake failed: ${handshake.slice(0, 120)}`));
        socket.destroy();
        return;
      }
      resolve(socket);
    });
    socket.on("error", reject);
  });
}

async function main() {
  const version = await requestJson("/json/version");
  if (!version.webSocketDebuggerUrl) {
    throw new Error("No browser websocket debugger URL found.");
  }
  const browser = await connectWebSocket(version.webSocketDebuggerUrl);
  let nextId = 1;
  const pending = new Map();
  let buffer = Buffer.alloc(0);

  browser.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (buffer.length >= 2) {
      const length = buffer[1] & 0x7f;
      const offset = length === 126 ? 4 : 2;
      const payloadLength = length === 126 ? buffer.readUInt16BE(2) : length;
      if (buffer.length < offset + payloadLength) return;
      const payload = buffer.slice(offset, offset + payloadLength).toString("utf8");
      buffer = buffer.slice(offset + payloadLength);
      const message = JSON.parse(payload);
      if (message.id && pending.has(message.id)) {
        pending.get(message.id)(message);
        pending.delete(message.id);
      }
    }
  });

  function call(method, params = {}) {
    const id = nextId++;
    sendWsFrame(browser, { id, method, params });
    return new Promise((resolve) => pending.set(id, resolve));
  }

  const { result: targetResult } = await call("Target.createTarget", { url: targetUrl });
  const pages = await requestJson("/json/list");
  const page = pages.find((item) => item.id === targetResult.targetId);
  if (!page) throw new Error("Created page was not visible through CDP list.");
  const tab = await connectWebSocket(page.webSocketDebuggerUrl);
  let tabId = 1;
  const tabPending = new Map();
  let tabBuffer = Buffer.alloc(0);

  tab.on("data", (chunk) => {
    tabBuffer = Buffer.concat([tabBuffer, chunk]);
    while (tabBuffer.length >= 2) {
      const length = tabBuffer[1] & 0x7f;
      const offset = length === 126 ? 4 : 2;
      const payloadLength = length === 126 ? tabBuffer.readUInt16BE(2) : length;
      if (tabBuffer.length < offset + payloadLength) return;
      const payload = tabBuffer.slice(offset, offset + payloadLength).toString("utf8");
      tabBuffer = tabBuffer.slice(offset + payloadLength);
      const message = JSON.parse(payload);
      if (message.id && tabPending.has(message.id)) {
        tabPending.get(message.id)(message);
        tabPending.delete(message.id);
      }
    }
  });

  function tabCall(method, params = {}) {
    const id = tabId++;
    sendWsFrame(tab, { id, method, params });
    return new Promise((resolve) => tabPending.set(id, resolve));
  }

  await tabCall("Runtime.enable");
  await tabCall("Emulation.setDeviceMetricsOverride", {
    width: 1440,
    height: 1100,
    deviceScaleFactor: 1,
    mobile: false
  });
  await tabCall("Page.navigate", { url: targetUrl });
  await new Promise((resolve) => setTimeout(resolve, 500));
  const expression = fs.readFileSync(__filename, "utf8") && `(() => {
    const list = document.querySelector(".partner-topic-list");
    if (!list) return { ok: false, reason: "missing partner-topic-list" };
    const contentShell = document.querySelector(".content-shell");
    const before = list.scrollTop;
    const wheelEvent = new WheelEvent("wheel", { deltaY: 220, bubbles: true, cancelable: true });
    const wheelAllowed = list.dispatchEvent(wheelEvent);
    list.scrollTop = 80;
    const after = list.scrollTop;
    const hasInternalScroll = list.scrollHeight > list.clientHeight + 1;
    const expandedCard = list.querySelector(".partner-topic-card.expanded");
    const expandedDetail = expandedCard ? expandedCard.querySelector(".partner-topic-detail") : null;
    const cardNotClipped = expandedCard ? expandedCard.scrollHeight <= expandedCard.clientHeight + 1 : false;
    const detailVisible = expandedCard && expandedDetail
      ? expandedDetail.getBoundingClientRect().bottom <= expandedCard.getBoundingClientRect().bottom + 1
      : false;
    const filler = document.createElement("div");
    filler.style.height = "360px";
    filler.style.flex = "0 0 auto";
    list.appendChild(filler);
    list.scrollTop = 0;
    const listCanScrollWithFiller = list.scrollHeight > list.clientHeight + 1;
    const listWheel = new WheelEvent("wheel", { deltaY: 220, bubbles: true, cancelable: true });
    const listWheelAllowed = list.dispatchEvent(listWheel);
    const listWheelDefaultPrevented = listWheel.defaultPrevented;
    list.scrollTop = 80;
    const topicManualScrollTop = list.scrollTop;
    list.scrollTop = 0;
    const profileCard = document.querySelector(".partner-contact-profile .profile-card");
    const profile = document.querySelector(".partner-contact-profile");
    const profileStyle = profile ? getComputedStyle(profile) : null;
    const contactList = document.querySelector(".partner-contact-list");
    const contactListStyle = contactList ? getComputedStyle(contactList) : null;
    contentShell && contentShell.scrollTo(0, 0);
    const profileWheel = new WheelEvent("wheel", { deltaY: 220, bubbles: true, cancelable: true });
    const profileWheelAllowed = profileCard ? profileCard.dispatchEvent(profileWheel) : false;
    const topicAfterProfileWheel = list.scrollTop;
    const contactListWheel = new WheelEvent("wheel", { deltaY: 220, bubbles: true, cancelable: true });
    const contactListWheelAllowed = contactList ? contactList.dispatchEvent(contactListWheel) : false;
    contentShell && contentShell.scrollTo(0, 120);
    const contentAfterManualScroll = contentShell ? contentShell.scrollTop : 0;
    const contentCanScroll = contentShell ? contentShell.scrollHeight > contentShell.clientHeight + 1 : false;
    filler.remove();
    return {
      ok: list.clientHeight >= 180 && cardNotClipped && detailVisible && profileStyle && contactListStyle && profileStyle.overscrollBehaviorY !== "contain" && profileStyle.overflowY !== "auto" && contactListStyle.overscrollBehaviorY === "contain" && contactListStyle.overflowY === "auto" && contactListWheelAllowed && !contactListWheel.defaultPrevented && listCanScrollWithFiller && listWheelAllowed && !listWheelDefaultPrevented && topicManualScrollTop > 0 && topicAfterProfileWheel === 0 && profileWheelAllowed && !profileWheel.defaultPrevented && (!contentCanScroll || contentAfterManualScroll > 0) && (hasInternalScroll ? after > before : wheelAllowed && !wheelEvent.defaultPrevented),
      clientHeight: list.clientHeight,
      scrollHeight: list.scrollHeight,
      before,
      after,
      hasInternalScroll,
      wheelAllowed,
      defaultPrevented: wheelEvent.defaultPrevented,
      cardClientHeight: expandedCard ? expandedCard.clientHeight : 0,
      cardScrollHeight: expandedCard ? expandedCard.scrollHeight : 0,
      cardNotClipped,
      detailVisible,
      listCanScrollWithFiller,
      listWheelAllowed,
      listWheelDefaultPrevented,
      topicManualScrollTop,
      topicAfterProfileWheel,
      profileWheelAllowed,
      profileWheelDefaultPrevented: profileWheel.defaultPrevented,
      profileOverflowY: profileStyle ? profileStyle.overflowY : "",
      profileOverscrollBehaviorY: profileStyle ? profileStyle.overscrollBehaviorY : "",
      contactListWheelAllowed,
      contactListWheelDefaultPrevented: contactListWheel.defaultPrevented,
      contactListOverflowY: contactListStyle ? contactListStyle.overflowY : "",
      contactListOverscrollBehaviorY: contactListStyle ? contactListStyle.overscrollBehaviorY : "",
      contentCanScroll,
      contentAfterManualScroll,
      layoutHeight: list.closest(".partner-contact-layout")?.getBoundingClientRect().height || 0,
      profileHeight: list.closest(".partner-contact-profile")?.getBoundingClientRect().height || 0,
      profileRows: getComputedStyle(list.closest(".partner-contact-profile")).gridTemplateRows,
      listDisplay: getComputedStyle(list).display,
      listOverflowY: getComputedStyle(list).overflowY
    };
  })()`;
  const evaluation = await tabCall("Runtime.evaluate", { expression, returnByValue: true });
  const value = evaluation.result && evaluation.result.result && evaluation.result.result.value;
  await call("Target.closeTarget", { targetId: targetResult.targetId });
  browser.end();
  tab.end();

  if (!value || !value.ok) {
    console.error(`Partner topic list is not scrollable: ${JSON.stringify(value)}`);
    process.exit(1);
  }
  console.log(`Partner topic list scrollable: clientHeight=${value.clientHeight}, scrollHeight=${value.scrollHeight}, scrollTop=${value.after}`);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
