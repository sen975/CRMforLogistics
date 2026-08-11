import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(process.argv[2], 'utf8');
assert.match(html, /function mountWeComSegmentFrame\s*\(/);
assert.match(html, /wx:for="\{\{data\.msgList\}\}"/);
assert.match(html, /wx:key="msgid"/);
assert.match(html, /message-id="\{\{item\.msgid\}\}"/);
assert.match(html, /secret-key="\{\{item\.secretKey\}\}"/);
assert.match(html, /open-type="viewMessage"/);
assert.match(html, /class="wecom-segment-row \{\{item\.direction\}\}"/);
assert.match(html, /class="wecom-segment-bubble"/);
assert.match(html, /\.wecom-segment-row\.inbound \{ justify-content:flex-start; \}/);
assert.match(html, /\.wecom-segment-row\.outbound \{ justify-content:flex-end; \}/);
assert.match(html, /\.wecom-segment-bubble \{[^}]*max-width:78%;/);
assert.match(html, /\.wecom-segment-row\.outbound \.wecom-segment-bubble \{[^}]*background:#f4fbf7;/);
assert.doesNotMatch(html, /display-type=/);
assert.doesNotMatch(html, /ww-open-message\s*\{/);
assert.doesNotMatch(html, /data-wecom-message-id/);

const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1] || '';
const errorStart = script.indexOf('function weComErrorField');
const errorEnd = script.indexOf('\n\n    function pumpWeComRenderQueue', errorStart);
const start = script.indexOf('function mountWeComSegmentFrame');
const end = script.indexOf('\n\n    function weComReferencesForSegment', start);
assert.ok(start >= 0 && end > start, 'segment mount source must be present');

class FakeClassList {
  constructor() { this.values = new Set(); }
  add(...values) { values.forEach(value => this.values.add(value)); }
  remove(...values) { values.forEach(value => this.values.delete(value)); }
  contains(value) { return this.values.has(value); }
}

const host = () => ({
  classList:new FakeClassList(),
  children:[],
  isConnected:true,
  replaceChildren(...children) { this.children = children; },
  querySelector(selector) {
    return selector === '.wecom-segment-frame' ? this.children[0] || null : null;
  }
});
const registry = new Map();
const creating = new Map();
const configs = [];
const diagnostics = [];
const lifecycle = [];
let destroyed = 0;
const context = {
  console:{
    ...console,
    warn(label, diagnostic) { diagnostics.push({ label, diagnostic }); },
    info(label, diagnostic) { lifecycle.push({ label, diagnostic }); }
  },
  setTimeout,
  clearTimeout,
  weComRenderGeneration:1,
  weComSegmentFrameRegistry:registry,
  weComCreatingSegmentKeys:creating,
  reserveWeComSegmentCapacity(contactPointId, key) { creating.set(key, { contactPointId }); return true; },
  setWeComSegmentStatus(target) { target.classList.remove('pending'); target.failed = true; },
  collapseWeComInlinePreviewForHost() {},
  createWeComSegmentFrame(target) { const frame = {}; target.replaceChildren(frame); return { frame }; },
  openWeComInlinePreview() { return true; },
  isWeComLoginExpired() { return false; },
  reportWeComViewerEvent() {},
  setWeComLoginStatus() {},
  returnToWeComLogin:async () => {},
  ww:{ createOpenDataFrameFactory() { return { createOpenDataFrame(config) {
    configs.push(config);
    return { destroy() { destroyed += 1; } };
  } }; } }
};
context.globalThis = context;
vm.createContext(context);
vm.runInContext(script.slice(errorStart, errorEnd), context);
vm.runInContext(script.slice(start, end), context);

const references = [
  { msgid:'m1', secretKey:'s1', direction:'inbound' },
  { msgid:'m2', secretKey:'s2', direction:'outbound' },
  { msgid:'m3', secretKey:'s3', direction:'inbound' }
];
const firstHost = host();
const firstMount = context.mountWeComSegmentFrame(
  firstHost, 'segment-1', references, { viewerSessionId:'viewer-1' }, 'token', 'contact-1', 1);
assert.equal(firstHost.classList.contains('pending'), true,
  'a segment must stay hidden before handleMounted');
assert.equal(configs.length, 1, 'one segment must create one OpenDataFrame');
assert.deepEqual(Array.from(configs[0].data.msgList, item => item.msgid), ['m1', 'm2', 'm3']);
assert.deepEqual(Array.from(configs[0].data.msgList, item => item.direction),
  ['inbound', 'outbound', 'inbound']);
configs[0].methods.handleSegmentMessageClick({ currentTarget:{ dataset:{ index:'1' } } });
configs[0].handleMounted();
const mounted = await firstMount;
assert.equal(mounted.status, 'mounted');
assert.deepEqual(lifecycle.map(entry => entry.label), [
  '[wecom-viewer/frame]',
  '[wecom-viewer/frame]'
]);
assert.deepEqual(lifecycle.map(entry => entry.diagnostic.stage), ['create_start', 'mounted']);
assert.equal(lifecycle[0].diagnostic.segmentId, 'segment-1');
assert.equal(lifecycle[0].diagnostic.messageCount, 3);
assert.equal(firstHost.classList.contains('pending'), false,
  'handleMounted must reveal the completed segment');
assert.equal(registry.get('contact-1:segment-1').activeMessageId, 'm2');

const reused = await context.mountWeComSegmentFrame(
  firstHost, 'segment-1', references, { viewerSessionId:'viewer-1' }, 'token', 'contact-1', 1);
assert.equal(reused.reused, true);
assert.equal(configs.length, 1, 'the same segment and host must reuse its OpenDataFrame');

firstHost.replaceChildren();
const staleMountedRemount = context.mountWeComSegmentFrame(
  firstHost, 'segment-1', references, { viewerSessionId:'viewer-1' }, 'token', 'contact-1', 1);
assert.equal(configs.length, 2,
  'a mounted registry entry without its frame DOM must create a replacement OpenDataFrame');
assert.equal(destroyed, 1,
  'a mounted registry entry without its frame DOM must destroy the stale instance');
configs[1].handleMounted();
assert.equal((await staleMountedRemount).reused, undefined);
assert.equal(registry.get('contact-1:segment-1').host, firstHost);

const replacementHost = host();
const replacement = context.mountWeComSegmentFrame(
  replacementHost, 'segment-1', references, { viewerSessionId:'viewer-1' }, 'token', 'contact-1', 1);
const frameError = new Error('replacement failed');
frameError.errCode = 40001;
frameError.errMsg = 'frame request rejected';
frameError.secretKey = 'must-not-leak';
frameError.accessToken = 'must-not-leak';
frameError.modalUrl = 'https://secret.example/preview';
frameError.self = frameError;
configs[2].error(frameError);
assert.equal((await replacement).status, 'failed');
assert.strictEqual(registry.get('contact-1:segment-1').host, firstHost,
  'a failed replacement must preserve the last mounted segment');
assert.equal(destroyed, 2, 'the stale instance and failed replacement instance must be destroyed');

configs[1].methods.handleSegmentMessageError(new Error('late component error'));
assert.equal(destroyed, 3,
  'a component error after handleMounted must destroy the mounted OpenDataFrame');
assert.equal(registry.has('contact-1:segment-1'), false,
  'a late component error must remove the stale mounted registry entry');
assert.equal(firstHost.failed, true,
  'a late component error must leave a retryable compact failure state');
assert.equal(diagnostics.length, 2, 'each SDK error callback must emit one safe diagnostic');
assert.equal(diagnostics[0].label, '[wecom-viewer/component-error]');
assert.equal(diagnostics[0].diagnostic.source, 'frame_error');
assert.equal(diagnostics[0].diagnostic.segmentId, 'segment-1');
assert.equal(diagnostics[0].diagnostic.messageCount, 3);
assert.equal(diagnostics[0].diagnostic.name, 'Error');
assert.equal(diagnostics[0].diagnostic.message, 'replacement failed');
assert.equal(diagnostics[0].diagnostic.errCode, '40001');
assert.equal(diagnostics[0].diagnostic.errMsg, 'frame request rejected');
assert.equal(diagnostics[1].diagnostic.source, 'message_binderror');
const serializedDiagnostics = JSON.stringify(diagnostics);
assert.doesNotMatch(serializedDiagnostics, /must-not-leak|secretKey|accessToken|modalUrl|msgid|m1|s1/);

console.log('wecom segment frame contract ok');
