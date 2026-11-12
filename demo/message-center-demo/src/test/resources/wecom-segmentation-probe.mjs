import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(process.argv[2], 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1] || '';
const start = script.indexOf('function weComLayoutMode');
const end = script.indexOf('\n\n    function bindThreadInteractions', start);
assert.ok(start >= 0 && end > start, 'timeline segmentation source must be present');

const context = {
  WECOM_SEGMENT_MESSAGE_LIMIT:15,
  WECOM_MIXED_SEGMENT_MAX:6,
  state:{ selectedPointId:'contact-1', selectedMessageId:'' },
  weComSegmentMessages:new Map(),
  esc:value => String(value ?? ''),
  timeText:value => String(value ?? ''),
  timelineItemFingerprint:item => item.sortId || item.payload?.id || '',
  timelineItemKey:item => item.sortId || item.payload?.id || '',
  label:value => String(value ?? ''),
  statusClass:() => '',
  statusText:() => '',
  hasMedia:() => false,
  mediaUrl:() => '',
  mediaPreviewHtml:() => '',
  bubbleText:() => '',
  renderCallRecordCard:() => ''
};
context.globalThis = context;
vm.createContext(context);
vm.runInContext(script.slice(start, end), context);

assert.equal(context.weComLayoutMode({ channels:['wecom'] }), 'standalone');
assert.equal(context.weComLayoutMode({ channels:['wecom', 'email'] }), 'mixed');
assert.equal(context.weComLayoutMode({ points:[{ channel:'wecom' }, { channel:'phone' }] }), 'mixed');

const message = (channel, id) => ({
  type:'message',
  sortId:`message:${id}`,
  payload:{ id, sourceId:id, channel }
});
const email = id => message('email', id);
const wecom = id => message('wecom', id);
const chatapp = id => message('chatapp', id);

const units = context.segmentTimelineItems([
  email('e1'), wecom('w1'), wecom('w2'), chatapp('c1'),
  wecom('w3'), ...Array.from({ length:15 }, (_, index) => wecom(`tail-${index + 1}`))
], 6, true);

assert.deepEqual(Array.from(units, unit => unit.kind), [
  'item', 'wecom-segment', 'item', 'wecom-segment', 'wecom-segment', 'wecom-segment'
]);
assert.deepEqual(Array.from(units[1].items, item => item.payload.sourceId), ['w1', 'w2']);
assert.deepEqual(Array.from(units.slice(3), unit => unit.items.length), [5, 5, 6]);

const standaloneWindow = context.standaloneWeComWindow(
  Array.from({ length:16 }, (_, index) => wecom(`standalone-${index + 1}`)), 15);
assert.deepEqual(Array.from(standaloneWindow, item => item.payload.sourceId),
  Array.from({ length:15 }, (_, index) => `standalone-${index + 2}`));
const standalone = context.segmentTimelineItems(standaloneWindow, 15, false);
assert.deepEqual(Array.from(standalone, unit => unit.items.length), [15]);

const mergedStandalone = [
  ...Array.from({ length:10 }, (_, index) => wecom(`older-${index + 1}`)),
  ...Array.from({ length:10 }, (_, index) => wecom(`current-${index + 1}`))
];
const standaloneHtml = context.threadMessagesHtml(mergedStandalone, 'contact-1', 'standalone');
assert.equal((standaloneHtml.match(/data-wecom-segment-id=/g) || []).length, 1,
  'standalone timeline must render exactly one OpenDataFrame host');
const standaloneSegmentId = standaloneHtml.match(/data-wecom-segment-id="([^"]+)"/)?.[1] || '';
assert.deepEqual(
  Array.from(context.weComSegmentMessages.get(standaloneSegmentId) || [], item => item.sourceId),
  [
    ...Array.from({ length:5 }, (_, index) => `older-${index + 6}`),
    ...Array.from({ length:10 }, (_, index) => `current-${index + 1}`)
  ],
  'standalone history merge must bind the rendered latest 15 messages to its only host'
);
assert.equal(context.weComSegmentId('contact-1', units[1].items),
  context.weComSegmentId('contact-1', units[1].items));
assert.notEqual(context.weComSegmentId('contact-1', units[1].items),
  context.weComSegmentId('contact-1', [...units[1].items].reverse()));

console.log('wecom segmentation behavior ok');
