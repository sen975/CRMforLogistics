import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(process.argv[2], 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1] || '';
const start = script.indexOf('function timelineItemKey');
const end = script.indexOf('\n\n    function renderThreadMessages', start);
assert.ok(start >= 0 && end > start, 'stable timeline source must be present');
const timelineSource = script.slice(start, end);

function parseRows(markup) {
  const starts = Array.from(markup.matchAll(/<div class="message-row /g)).map(match => match.index);
  return starts.map((position, index) => {
    const part = markup.slice(position, starts[index + 1] ?? markup.length);
    const timelineKey = part.match(/data-timeline-key="([^"]*)"/)?.[1] || '';
    const fingerprint = part.match(/data-render-fingerprint="([^"]*)"/)?.[1] || '';
    const rowClass = part.match(/<div class="([^"]*)" data-timeline-key/)?.[1] || '';
    const messageClass = part.match(/<article class="([^"]*)"/)?.[1] || '';
    const messageId = part.match(/data-id="([^"]*)"/)?.[1] || '';
    const wecomSegmentId = part.match(/data-wecom-segment-id="([^"]*)"/)?.[1] || '';
    const message = { className:messageClass, dataset:{ id:messageId } };
    const wecomHost = wecomSegmentId ? {
      dataset:{ wecomSegmentId },
      classList:new FakeClassList(),
      children:[],
      appendChild(child) { this.children.push(child); return child; },
      querySelector(selector) {
        if (selector === '.wecom-message-preview') {
          return this.children.find(child => child.className === 'wecom-message-preview') || null;
        }
        return null;
      }
    } : null;
    const metaText = part.match(/<div class="msg-meta-line">([\s\S]*?)<\/div>/)?.[1] || '';
    const meta = {
      childNodes:[metaText],
      replaceChildren(...nodes) { this.childNodes = nodes; }
    };
    return {
      className:rowClass,
      dataset:{ timelineKey, renderFingerprint:fingerprint },
      querySelector(selector) {
        if (selector === '[data-wecom-segment-id]') return wecomHost;
        if (selector === '.msg[data-id]') return message;
        if (selector === '.msg-meta-line') return meta;
        return null;
      }
    };
  });
}

class FakeClassList {
  constructor() { this.values = new Set(); }
  add(...values) { values.forEach(value => this.values.add(value)); }
  contains(value) { return this.values.has(value); }
}

const document = {
  createElement() {
    return {
      children:[],
      set innerHTML(value) { this.children = parseRows(String(value || '')); }
    };
  },
  createDocumentFragment() {
    return {
      children:[],
      appendChild(child) { this.children.push(child); }
    };
  }
};
const target = {
  children:[],
  querySelectorAll() { return this.children; },
  replaceChildren(fragment) { this.children = [...fragment.children]; }
};
let pruneCalls = 0;
let framePruneCalls = 0;
const context = {
  assert,
  document,
  WECOM_SEGMENT_MESSAGE_LIMIT:15,
  WECOM_MIXED_SEGMENT_MAX:6,
  weComSegmentMessages:new Map(),
  pruneDisconnectedWeComInlinePreviews() { pruneCalls++; },
  pruneDisconnectedWeComSegmentFrames() { framePruneCalls++; },
  state:{ selectedMessageId:'', selectedPointId:'contact-1' },
  esc:value => String(value ?? ''),
  label:value => value,
  timeText:value => value,
  statusClass:() => '',
  statusText:() => '',
  hasMedia:() => false,
  mediaPreviewHtml:() => '',
  bubbleText:message => message.text || message.summary || '',
  renderCallRecordCard:() => '',
  weComMessageId:message => String(message.sourceId || message.id || '').replace(/^wecom:/, '')
};
context.globalThis = context;
vm.createContext(context);
vm.runInContext(timelineSource, context);

const wecom = (text, timestamp = '2026-08-07T01:00:00Z') => ({
  type:'message',
  occurredAt:timestamp,
  sortId:'message:wecom-1',
  payload:{
    id:'wecom:wecom-1',
    sourceId:'wecom-1',
    channel:'wecom',
    direction:'inbound',
    timestamp,
    text
  }
});
const chat = text => ({
  type:'message',
  occurredAt:'2026-08-07T01:01:00Z',
  sortId:'message:chat-1',
  payload:{
    id:'chat-1',
    channel:'chatapp',
    direction:'inbound',
    timestamp:'2026-08-07T01:01:00Z',
    text
  }
});
const older = {
  type:'message',
  occurredAt:'2026-08-07T00:59:00Z',
  sortId:'message:older',
  payload:{
    id:'older',
    channel:'chatapp',
    direction:'inbound',
    timestamp:'2026-08-07T00:59:00Z',
    text:'older'
  }
};

context.reconcileThreadMessages({}, [wecom('first'), chat('first')], target);
const originalWeComRow = target.children[0];
const originalWeComHost = originalWeComRow.querySelector('[data-wecom-segment-id]');
const originalWeComMeta = originalWeComRow.querySelector('.msg-meta-line');
const originalChatRow = target.children[1];
const originalPreview = { className:'wecom-message-preview' };
originalWeComHost.classList.add('expanded');
originalWeComHost.appendChild(originalPreview);

context.reconcileThreadMessages({}, [
  older,
  wecom('server projection changed', '2026-08-07T02:00:00Z'),
  chat('updated')
], target);
assert.equal(target.children[0].dataset.timelineKey, 'message:message:older');
assert.strictEqual(target.children[1], originalWeComRow,
  'history insertion must preserve the mounted WeCom row object');
assert.strictEqual(target.children[1].querySelector('[data-wecom-segment-id]'), originalWeComHost,
  'history insertion must preserve the mounted OpenDataFrame host object');
assert.equal(originalWeComHost.classList.contains('expanded'), true,
  'history insertion must preserve the expanded host state');
assert.strictEqual(originalWeComHost.querySelector('.wecom-message-preview'), originalPreview,
  'history insertion must preserve the inline preview object');
assert.strictEqual(target.children[1].querySelector('.msg-meta-line'), originalWeComMeta,
  'the existing metadata container must be updated in place');
assert.match(String(originalWeComMeta.childNodes[0]), /2026-08-07T02:00:00Z/,
  'the reused WeCom row must receive fresh projected metadata');
assert.notStrictEqual(target.children[2], originalChatRow,
  'an updated ordinary message may replace its row');

context.reconcileThreadMessages({}, [
  older,
  wecom('another refresh', '2026-08-07T03:00:00Z'),
  chat('updated')
], target);
assert.strictEqual(target.children[1], originalWeComRow,
  'background refresh must preserve the mounted WeCom row object');
assert.strictEqual(target.children[1].querySelector('[data-wecom-segment-id]'), originalWeComHost,
  'background refresh must preserve the OpenDataFrame host object');
assert.equal(originalWeComHost.classList.contains('expanded'), true,
  'background refresh must preserve the expanded host state');
assert.strictEqual(originalWeComHost.querySelector('.wecom-message-preview'), originalPreview,
  'background refresh must preserve the inline preview object');
assert.equal(pruneCalls, 3,
  'each timeline commit must prune previews whose hosts left the DOM');
assert.equal(framePruneCalls, 3,
  'each timeline commit must prune OpenDataFrames whose segments left the DOM');

console.log('wecom stable timeline behavior ok');
