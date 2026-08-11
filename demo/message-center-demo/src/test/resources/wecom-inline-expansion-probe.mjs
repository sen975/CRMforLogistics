import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(process.argv[2], 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
assert.ok(script, 'page must contain the embedded script');

function extractFunctionRange(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start);
  assert.ok(start >= 0, `missing script marker: ${startMarker}`);
  assert.ok(end > start, `missing script end marker: ${endMarker}`);
  return source.slice(start, end);
}

assert.ok(extractFunctionRange(script,
  'async function returnToWeComLogin', 'async function handleViewerAuthFailure')
  .includes('clearWeComInlinePreviews();'),
  'returning to login must clear every inline preview');
assert.ok(extractFunctionRange(script,
  'function invalidateWeComContactWindow', 'function weComContactWindowUsable')
  .includes('clearWeComInlinePreviews(contactPointId);'),
  'invalidating a contact window must clear that contact previews');
assert.ok(extractFunctionRange(script,
  'function resetWeComContactFrames', 'function cancelWeComRenderWork')
  .includes('clearWeComInlinePreviews(contactPointId);'),
  'resetting contact frames must clear that contact previews');
assert.ok(extractFunctionRange(script,
  'function trimWeComSegmentFrames', 'function protectedWeComSegmentKeys')
  .includes('collapseWeComInlinePreviewForHost(entry.host);'),
  'registry trimming must collapse previews by host before eviction');
assert.ok(extractFunctionRange(script,
  'function reserveWeComSegmentCapacity', 'function mountWeComSegmentFrame')
  .includes('collapseWeComInlinePreviewForHost(entry.host);'),
  'capacity eviction must collapse previews by host first');
assert.ok(extractFunctionRange(script,
  'function retainRecentWeComFrames', 'function invalidateWeComContactWindow')
  .includes('collapseWeComInlinePreviewForHost(entry.host);'),
  'contact window trimming must collapse previews by host first');
assert.equal(extractFunctionRange(script,
  'async function retryWeComSegment', 'function mountLocalWeComTimelineMessages')
  .includes('collapseWeComInlinePreview(key);'), false,
  'retry must preserve the previous preview while the replacement is pending');
assert.ok(extractFunctionRange(script,
  'function mountWeComSegmentFrame', 'function weComReferencesForSegment')
  .includes("if (result.status === 'mounted') {\n            collapseWeComInlinePreviewForHost(existing?.host || host);"),
  'a successful replacement must release the previous host previews');
assert.ok(extractFunctionRange(script,
  'function clearWeComRendererState', 'async function enableNotifications')
  .includes('clearWeComInlinePreviews();'),
  'renderer teardown must clear every inline preview');
assert.equal(script.includes("window.addEventListener?.('beforeunload', clearWeComRendererState);"), false,
  'beforeunload must not disable or pre-clear BFCache state');
assert.ok(script.includes("window.addEventListener?.('pagehide', handleWeComPageHide);"),
  'pagehide must use the BFCache-aware renderer handler');

class FakeClassList {
  constructor() { this.values = new Set(); }
  add(...values) { values.forEach(value => this.values.add(value)); }
  remove(...values) { values.forEach(value => this.values.delete(value)); }
  contains(value) { return this.values.has(value); }
}

class FakeElement {
  constructor(tagName) {
    this.tagName = tagName.toUpperCase();
    this.children = [];
    this.parentNode = null;
    this.classList = new FakeClassList();
    this.dataset = {};
    this.style = {};
    this.attributes = {};
    this.isConnected = true;
    this._rect = { top: 100, bottom: 200 };
    this.src = '';
  }
  appendChild(child) {
    if (this.failPreviewAppend && child.className === 'wecom-message-preview') {
      throw new Error('preview mount failed');
    }
    child.parentNode = this;
    if (child.className === 'wecom-message-preview') child._rect = this._rect;
    this.children.push(child);
    return child;
  }
  append(...children) { children.forEach(child => this.appendChild(child)); }
  remove() {
    if (!this.parentNode) return;
    this.parentNode.children = this.parentNode.children.filter(child => child !== this);
    this.parentNode = null;
    this.isConnected = false;
  }
  replaceChildren(...children) {
    this.children.forEach(child => { child.parentNode = null; child.isConnected = false; });
    this.children = [];
    children.forEach(child => this.appendChild(child));
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getBoundingClientRect() { return this._rect; }
  querySelector(selector) {
    if (selector === 'iframe') {
      return this.children.find(child => child.tagName === 'IFRAME')
        || this.children.map(child => child.querySelector(selector)).find(Boolean);
    }
    return null;
  }
}

const createdIframes = [];
const document = {
  createElement(tagName) {
    const element = new FakeElement(tagName);
    if (tagName === 'iframe') createdIframes.push(element);
    return element;
  }
};
const window = {
  innerHeight: 600,
  location: { href: 'https://message-center.test/' },
  matchMedia: () => ({ matches: false })
};
const context = { document, window, URL, console, Math, Number, Set, Map, Boolean };
let extracted = extractFunctionRange(
  script,
  'const WECOM_EXPANDED_PREVIEW_LIMIT = 15;',
  'function setWeComSegmentStatus'
);
extracted += '\n' + extractFunctionRange(
  script,
  'function pruneExpiredWeComContactWindows',
  'function retainRecentWeComFrames'
);
extracted += '\n' + extractFunctionRange(
  script,
  'function invalidateWeComContactWindow',
  'function weComContactWindowUsable'
);
extracted += '\n' + extractFunctionRange(
  script,
  'function setWeComViewerLoadFailureStatuses',
  'function setWeComSegmentStatus'
);
extracted += '\n' + extractFunctionRange(
  script,
  'function setWeComSegmentStatus',
  'function setWeComTitleLoading'
);
extracted += '\nlet __rendererClearCalls = 0;\n'
  + 'function clearWeComRendererState() { __rendererClearCalls += 1; }\n'
  + extractFunctionRange(script, 'function handleWeComPageHide', 'async function enableNotifications');
extracted += '\n' + [
  'globalThis.__weComExpandedPreviews = weComExpandedPreviews;',
  'globalThis.__weComPreviewHeight = weComPreviewHeight;',
  'globalThis.__openWeComInlinePreview = openWeComInlinePreview;',
  'globalThis.__clearWeComInlinePreviews = clearWeComInlinePreviews;',
  'globalThis.__pruneDisconnectedWeComInlinePreviews = pruneDisconnectedWeComInlinePreviews;',
  'globalThis.__pruneDisconnectedWeComSegmentFrames = pruneDisconnectedWeComSegmentFrames;',
  'globalThis.__pruneExpiredWeComContactWindows = pruneExpiredWeComContactWindows;',
  'globalThis.__handleWeComPageHide = handleWeComPageHide;',
  'globalThis.__setWeComTimelineHostStatus = setWeComSegmentStatus;',
  'globalThis.__setWeComViewerLoadFailureStatuses = setWeComViewerLoadFailureStatuses;',
  'globalThis.__weComFrameRegistry = weComSegmentFrameRegistry;',
  'globalThis.__weComContactWindows = weComContactWindows;',
  'globalThis.__setWeComCommittedContactPointId = value => { weComCommittedContactPointId = value; };',
  'globalThis.__setWeComTimelineViewer = value => { weComTimelineViewer = value; };',
  'globalThis.__getWeComTimelineViewer = () => weComTimelineViewer;',
  'globalThis.__getRendererClearCalls = () => __rendererClearCalls;'
].join('\n');
vm.runInNewContext(extracted, context);

const previewHeight = context.__weComPreviewHeight;
const openInlinePreview = context.__openWeComInlinePreview;
const clearInlinePreviews = context.__clearWeComInlinePreviews;
const pruneDisconnectedPreviews = context.__pruneDisconnectedWeComInlinePreviews;
const pruneDisconnectedSegmentFrames = context.__pruneDisconnectedWeComSegmentFrames;
const pruneExpiredWindows = context.__pruneExpiredWeComContactWindows;
const handlePageHide = context.__handleWeComPageHide;
const setTimelineHostStatus = context.__setWeComTimelineHostStatus;
const setViewerLoadFailureStatuses = context.__setWeComViewerLoadFailureStatuses;
const expandedPreviews = context.__weComExpandedPreviews;
assert.equal(previewHeight({ height: 80 }), 120);
assert.equal(previewHeight({ height: 900 }), 560);
assert.equal(previewHeight({}), 360);
window.matchMedia = query => ({ matches: query === '(max-width: 640px)' });
assert.equal(previewHeight({ height: 900 }), 360);

const hosts = Array.from({ length: 20 }, () => new FakeElement('div'));
hosts[0]._rect = { top: -100, bottom: -50 };
hosts.forEach(host => host.appendChild(new FakeElement('div')));

assert.equal(openInlinePreview(hosts[0], 'contact-1', 'm1', {
  modalUrl: 'https://work.weixin.qq.com/preview/1', modalSize: { height: 240 }
}), true);
const loadingIframe = hosts[0].children[1].children[1].children[0];
assert.equal(loadingIframe.hidden, true,
  'an inline preview must stay hidden until its iframe loads');
loadingIframe.onload();
assert.equal(loadingIframe.hidden, false,
  'a loaded inline preview must become visible');
assert.equal(openInlinePreview(hosts[1], 'contact-1', 'm2', {
  modalUrl: 'https://work.weixin.qq.com/preview/2', modalSize: { height: 320 }
}), true);
assert.equal(expandedPreviews.size, 2,
  'two messages must remain expanded independently');
assert.equal(hosts[0].classList.contains('expanded'), true);
assert.equal(hosts[1].classList.contains('expanded'), true);
assert.equal(hosts[0].children[1].style.height, undefined,
  'the wrapper must grow around the toolbar, gap, padding and preview content');
assert.equal(hosts[0].children[1].children[1].style.height, '240px',
  'the suggested height must apply to the preview content');

for (let index = 3; index <= 16; index++) {
  openInlinePreview(hosts[index - 1], 'contact-1', `m${index}`, {
    modalUrl: `https://work.weixin.qq.com/preview/${index}`, modalSize: { height: 300 }
  });
}
assert.equal(expandedPreviews.size, 15);
assert.equal(hosts[0].classList.contains('expanded'), false,
  'the oldest offscreen preview must be collapsed first');

assert.equal(openInlinePreview(hosts[16], 'contact-1', 'bad', {
  modalUrl: 'http://example.com/insecure', modalSize: { height: 300 }
}), false);
assert.equal(hosts[16].children.length, 1,
  'invalid preview URL must leave the folded frame intact');

const collapseButton = hosts[1].children[1]?.children[0]?.children[0];
const collapsedIframe = hosts[1].children[1]?.children[1]?.children[0];
assert.ok(collapseButton, 'expanded message must have a collapse button');
collapseButton.onclick?.({ stopPropagation() {} });
assert.equal(collapsedIframe.src, 'about:blank',
  'collapsing must release the preview URL before removing the wrapper');
assert.equal(expandedPreviews.has('contact-1:m2'), false);
assert.equal(hosts[1].classList.contains('expanded'), false);
assert.equal(hosts[1].children.length, 1);
assert.equal(hosts[0].classList.contains('expanded'), false);
assert.equal(hosts[2].classList.contains('expanded'), true);
assert.equal(expandedPreviews.size, 14);

assert.equal(openInlinePreview(hosts[16], 'contact-1', 'm17', {
  modalUrl: 'https://work.weixin.qq.com/preview/17', modalSize: { height: 300 }
}), true);
expandedPreviews.forEach(entry => { entry.wrapper._rect = { top: 100, bottom: 200 }; });
assert.equal(openInlinePreview(hosts[17], 'contact-1', 'm18', {
  modalUrl: 'https://work.weixin.qq.com/preview/18', modalSize: { height: 300 }
}), true);
assert.equal(expandedPreviews.size, 15);
assert.equal(expandedPreviews.has('contact-1:m3'), false,
  'when every preview is visible, the oldest preview must be collapsed');

const registryKeysBeforeFailure = Array.from(expandedPreviews.keys());
hosts[18].failPreviewAppend = true;
assert.equal(openInlinePreview(hosts[18], 'contact-1', 'm19', {
  modalUrl: 'https://work.weixin.qq.com/preview/19', modalSize: { height: 300 }
}), false);
assert.deepEqual(Array.from(expandedPreviews.keys()), registryKeysBeforeFailure,
  'a failed mount must not evict or register any preview');
assert.equal(hosts[18].children.length, 1,
  'a failed mount must leave the folded frame intact');
assert.equal(hosts[18].classList.contains('expanded'), false);
assert.equal(createdIframes.at(-1).src, 'about:blank',
  'a failed mount must release the iframe URL');

const loadErrorHost = new FakeElement('div');
loadErrorHost.appendChild(new FakeElement('div'));
assert.equal(openInlinePreview(loadErrorHost, 'contact-1', 'load-error', {
  modalUrl: 'https://work.weixin.qq.com/preview/load-error', modalSize: { height: 300 }
}), true);
const loadErrorIframe = loadErrorHost.children[1].children[1].children[0];
loadErrorIframe.onerror();
assert.equal(loadErrorIframe.src, 'about:blank',
  'a failed iframe load must release its preview URL');
assert.equal(loadErrorHost.classList.contains('expanded'), false,
  'a failed iframe load must restore the compact segment');
assert.equal(expandedPreviews.has('contact-1:load-error'), false);

clearInlinePreviews();
const failedRefreshHost = new FakeElement('div');
failedRefreshHost.appendChild(new FakeElement('div'));
assert.equal(openInlinePreview(failedRefreshHost, 'contact-failed', 'failed', {
  modalUrl: 'https://work.weixin.qq.com/preview/failed', modalSize: { height: 300 }
}), true);
const failedRefreshIframe = failedRefreshHost.children[1].children[1].children[0];
setTimelineHostStatus(failedRefreshHost, '消息加载失败', false);
assert.equal(failedRefreshIframe.src, 'about:blank',
  'replacing an expanded host with status content must release the preview iframe');
assert.equal(expandedPreviews.has('contact-failed:failed'), false);
assert.equal(failedRefreshHost.classList.contains('expanded'), false);
assert.equal(failedRefreshHost.children.length, 1,
  'a refresh failure must leave one visible compact status frame');

const preservedSegmentHost = new FakeElement('div');
preservedSegmentHost.dataset.wecomSegmentId = 'preserved';
const preservedFrame = new FakeElement('div');
preservedSegmentHost.appendChild(preservedFrame);
const initialFailureHost = new FakeElement('div');
initialFailureHost.dataset.wecomSegmentId = 'initial';
context.__weComFrameRegistry.set('contact-refresh:preserved', {
  contactPointId:'contact-refresh', host:preservedSegmentHost, status:'mounted'
});
setViewerLoadFailureStatuses({
  querySelectorAll() { return [preservedSegmentHost, initialFailureHost]; }
}, 'contact-refresh');
assert.strictEqual(preservedSegmentHost.children[0], preservedFrame,
  'a session refresh failure must preserve a previously mounted segment frame');
assert.equal(initialFailureHost.children.length, 1,
  'a first-load failure must still show one compact segment status');
context.__weComFrameRegistry.clear();

clearInlinePreviews();
const contactOneHost = new FakeElement('div');
contactOneHost.appendChild(new FakeElement('div'));
const detachedHost = new FakeElement('div');
detachedHost.appendChild(new FakeElement('div'));
assert.equal(openInlinePreview(contactOneHost, 'contact-1', 'preserved', {
  modalUrl: 'https://work.weixin.qq.com/preview/preserved', modalSize: { height: 300 }
}), true);
assert.equal(openInlinePreview(detachedHost, 'contact-2', 'detached', {
  modalUrl: 'https://work.weixin.qq.com/preview/detached', modalSize: { height: 300 }
}), true);
const detachedIframe = detachedHost.children[1].children[1].children[0];
clearInlinePreviews('contact-1');
assert.equal(expandedPreviews.size, 1,
  'clearing one contact must preserve another contact');
detachedHost.isConnected = false;
pruneDisconnectedPreviews();
assert.equal(expandedPreviews.has('contact-2:detached'), false);
assert.equal(detachedIframe.src, 'about:blank',
  'pruning a detached host must release its iframe URL');

let detachedFrameDestroyed = false;
const detachedFrameHost = new FakeElement('div');
detachedFrameHost.isConnected = false;
context.__weComFrameRegistry.set('contact-frame:segment-frame', {
  contactPointId:'contact-frame', host:detachedFrameHost,
  instance:{ destroy() { detachedFrameDestroyed = true; } }
});
pruneDisconnectedSegmentFrames();
assert.equal(detachedFrameDestroyed, true,
  'a segment host removed from the timeline must destroy its OpenDataFrame');
assert.equal(context.__weComFrameRegistry.has('contact-frame:segment-frame'), false,
  'a removed timeline segment must leave the frame registry immediately');

const expiredHost = new FakeElement('div');
expiredHost.appendChild(new FakeElement('div'));
assert.equal(openInlinePreview(expiredHost, 'contact-expired', 'expired', {
  modalUrl: 'https://work.weixin.qq.com/preview/expired', modalSize: { height: 300 }
}), true);
const expiredIframe = expiredHost.children[1].children[1].children[0];
let frameDestroyed = false;
context.__weComFrameRegistry.set('contact-expired:expired', {
  contactPointId:'contact-expired', host:expiredHost,
  instance:{ destroy() { frameDestroyed = true; } }
});
context.__weComContactWindows.set('contact-expired', {
  contactPointId:'contact-expired',
  viewer:{ expiresAt:Date.now() - 1 },
  ready:true,
  container:new FakeElement('div')
});
context.__setWeComCommittedContactPointId('contact-expired');
context.__setWeComTimelineViewer({ contactPointId:'contact-expired', expiresAt:Date.now() - 1 });
pruneExpiredWindows();
assert.equal(expiredIframe.src, 'about:blank',
  'an expired committed viewer must release its inline iframe');
assert.equal(frameDestroyed, true,
  'an expired committed viewer must destroy its OpenDataFrame');
assert.equal(expandedPreviews.has('contact-expired:expired'), false);
assert.equal(context.__weComFrameRegistry.has('contact-expired:expired'), false);
assert.equal(context.__getWeComTimelineViewer(), null);
assert.equal(expiredHost.classList.contains('pending'), true,
  'an expired committed host must return to a reloadable state');

const rendererClearCalls = context.__getRendererClearCalls();
handlePageHide({ persisted:true });
assert.equal(context.__getRendererClearCalls(), rendererClearCalls,
  'entering BFCache must preserve the renderer state');
handlePageHide({ persisted:false });
assert.equal(context.__getRendererClearCalls(), rendererClearCalls + 1,
  'a terminal pagehide must clear the renderer state');

console.log('wecom inline expansion behavior ok');
