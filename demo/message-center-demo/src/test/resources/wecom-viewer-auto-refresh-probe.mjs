import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(process.argv[2], 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1] || '';
const start = script.indexOf('function currentWeComMessageIds');
const end = script.indexOf('\n\n    function weComViewerRefreshText', start);
assert.ok(start >= 0 && end > start, 'viewer auto-refresh source must be present');

let now = 1000000;
class ProbeDate extends Date {
  static now() { return now; }
}
const host = (segmentId, mounted = true) => ({
  dataset:{ wecomSegmentId:segmentId },
  classList:{ contains:() => !mounted },
  querySelector:() => mounted ? ({}) : null
});
const activeHost = host('segment-1');
const oldPendingHost = host('segment-old', false);
const thread = {
  hosts:[activeHost],
  querySelectorAll() {
    return this.hosts;
  }
};
const contact = { id:'contact-1', points:[{ id:'wecom:external-1', channel:'wecom' }] };
const loadCalls = [];
const mountCalls = [];
const context = {
  Date:ProbeDate,
  LOCAL_DEV_MODE:false,
  WECOM_VIEWER_AUTO_REFRESH_MS:60000,
  WECOM_VIEWER_MOUNT_RETRY_BASE_MS:5000,
  WECOM_VIEWER_MOUNT_RETRY_MAX_MS:60000,
  WECOM_ACTIVE_FRAME_LIMIT:30,
  WECOM_SEGMENT_MESSAGE_LIMIT:15,
  state:{ selectedPointId:'contact-1' },
  weComPreparingContactPointId:'',
  weComRenderGeneration:7,
  weComContactWindows:new Map(),
  weComSegmentMessages:new Map([
    ['segment-1', [{ sourceId:'m1' }]],
    ['segment-old', [{ sourceId:'m0' }]]
  ]),
  weComTimelineViewer:null,
  weComSegmentFrameRegistry:new Map(),
  weComViewerMountPromises:new Map(),
  weComViewerMountRetryState:new Map(),
  $:id => id === 'thread' ? thread : null,
  selectedContact:() => contact,
  weComLayoutMode:selected => selected?.channels?.length === 1 && selected.channels[0] === 'wecom'
    ? 'standalone' : 'mixed',
  wecomPoint:() => contact.points[0],
  weComMessageId:message => message.sourceId,
  pruneExpiredWeComContactWindows() {},
  invalidateWeComContactWindow() {},
  loadWeComViewer(selected, options) {
    loadCalls.push({ selected, options });
    return Promise.resolve();
  },
  mountWeComTimelineMessages(detail, viewerAuthToken, contactPointId, root, generation) {
    mountCalls.push({ detail, viewerAuthToken, contactPointId, root, generation });
    return Promise.resolve([]);
  },
  mountLocalWeComTimelineMessages() {
    throw new Error('local mount must not run in this probe');
  }
};
context.globalThis = context;
vm.createContext(context);
vm.runInContext(script.slice(start, end), context);

const timelineWeCom = id => ({ type:'message', payload:{ channel:'wecom', sourceId:id } });
const standaloneContact = { id:'contact-1', channels:['wecom'] };
const mixedContact = { id:'contact-1', channels:['wecom', 'email'] };
const olderItems = Array.from({ length:15 }, (_, index) => timelineWeCom(`old-${index + 1}`));
assert.deepEqual(Array.from(context.weComHistoryViewerMessageIds(
  standaloneContact, olderItems, thread)), ['m1'],
  'standalone history must request the actual rendered host window');
assert.deepEqual(Array.from(context.weComHistoryViewerMessageIds(
  mixedContact, olderItems, thread)), olderItems.map(item => item.payload.sourceId),
  'mixed history must request the newly loaded older page instead of the recent root tail');

context.weComSegmentFrameRegistry.set('contact-1:segment-1', {
  host:activeHost, status:'mounted'
});

const recent = () => ProbeDate.now() - 1000;
context.weComTimelineViewer = {
  contactPointId:'contact-1',
  loadedAt:recent(),
  expiresAt:Date.now() + 120000,
  detail:{ messages:[{ msgid:'m1' }] }
};
await context.refreshWeComViewerIfDue();
assert.equal(loadCalls.length, 0,
  'a recently loaded viewer must be reused when the timeline message IDs are unchanged');

context.weComSegmentMessages.set('segment-1', [{ sourceId:'m1' }, { sourceId:'m2' }]);
await context.refreshWeComViewerIfDue();
assert.equal(loadCalls.length, 1,
  'a new timeline message ID must bypass the 60 second viewer refresh interval');
assert.deepEqual(Array.from(loadCalls[0].options.messageIds), ['m1', 'm2'],
  'the immediate viewer request must contain the latest timeline message IDs');

loadCalls.length = 0;
context.weComTimelineViewer = {
  contactPointId:'contact-1',
  loadedAt:recent(),
  expiresAt:Date.now() + 120000,
  detail:{ messages:[{ msgid:'m1' }, { msgid:'m2' }] }
};
context.weComSegmentFrameRegistry.clear();
await context.refreshWeComViewerIfDue();
assert.equal(loadCalls.length, 0,
  'a recent viewer without mounted frames must not create a replacement viewer session');
assert.equal(mountCalls.length, 1,
  'a recent viewer without mounted frames must remount from its existing detail');
await context.refreshWeComViewerIfDue();
assert.equal(mountCalls.length, 1,
  'a failed remount must respect its retry cooldown on the next background tick');
now += 5000;
await context.refreshWeComViewerIfDue();
assert.equal(mountCalls.length, 2,
  'a failed remount may retry after the bounded cooldown expires');

loadCalls.length = 0;
mountCalls.length = 0;
context.weComViewerMountRetryState.clear();
thread.hosts = [oldPendingHost, activeHost];
const activeWindowMessages = Array.from({ length:15 }, (_, index) => ({ sourceId:`m${index + 1}` }));
context.weComSegmentMessages.set('segment-1', activeWindowMessages);
context.weComSegmentFrameRegistry.set('contact-1:segment-1', {
  host:activeHost, status:'mounted'
});
context.weComTimelineViewer = {
  contactPointId:'contact-1',
  loadedAt:ProbeDate.now() - 1000,
  expiresAt:ProbeDate.now() + 120000,
  detail:{ messages:activeWindowMessages.map(message => ({ msgid:message.sourceId })) }
};
await context.refreshWeComViewerIfDue();
assert.equal(loadCalls.length, 0,
  'an old pending host outside the active viewer message window must not recreate the session');
assert.equal(mountCalls.length, 0,
  'an old pending host outside the active viewer message window must not trigger remount');

const segmentFiveA = host('segment-five-a');
const segmentFiveB = host('segment-five-b');
const segmentSix = host('segment-six');
thread.hosts = [segmentFiveA, segmentFiveB, segmentSix];
context.weComSegmentMessages.set('segment-five-a',
  Array.from({ length:5 }, (_, index) => ({ sourceId:`s${index + 1}` })));
context.weComSegmentMessages.set('segment-five-b',
  Array.from({ length:5 }, (_, index) => ({ sourceId:`s${index + 6}` })));
context.weComSegmentMessages.set('segment-six',
  Array.from({ length:6 }, (_, index) => ({ sourceId:`s${index + 11}` })));
assert.deepEqual(Array.from(context.currentWeComMessageIds('contact-1', thread)),
  Array.from({ length:11 }, (_, index) => `s${index + 6}`),
  'the 15-message viewer budget must retain complete mixed segments instead of cutting 5/5/6');
const partiallyCoveredViewer = {
  detail:{ messages:Array.from({ length:15 }, (_, index) => ({ msgid:`s${index + 2}` })) }
};
assert.deepEqual(Array.from(context.weComViewerRenderableHosts(partiallyCoveredViewer, thread)),
  [segmentFiveB, segmentSix],
  'a partially covered mixed segment must not be treated as renderable');

thread.hosts = [activeHost];
context.weComTimelineViewer.loadedAt = ProbeDate.now() - 60001;
await context.refreshWeComViewerIfDue();
assert.equal(loadCalls.length, 1,
  'an unchanged viewer must refresh after the normal interval expires');

console.log('wecom viewer auto-refresh behavior ok');
