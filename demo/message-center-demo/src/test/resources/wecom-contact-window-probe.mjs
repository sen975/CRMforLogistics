import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(process.argv[2], 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1] || '';
const start = script.indexOf('function getOrCreateWeComContactWindow');
const end = script.indexOf('\n\n    async function selectContact', start);
assert.ok(start >= 0 && end > start, 'contact window source must be present');
const windowSource = script.slice(start, end);

function container(children = []) {
  return {
    children:[...children],
    querySelectorAll() { return []; },
    replaceChildren(...next) { this.children = next; },
    remove() { this.removed = true; }
  };
}

const oldRows = [{ id:'old-1' }, { id:'old-2' }];
const newRows = [{ id:'new-1' }, { id:'new-2' }];
const thread = container(oldRows);
thread.scrollHeight = 200;
thread.scrollTop = 0;
const oldWindow = {
  contactPointId:'contact-a',
  container:container(),
  viewer:null,
  ready:true,
  committed:true,
  generation:1,
  lastUsed:1
};
const newWindow = {
  contactPointId:'contact-b',
  container:container(newRows),
  viewer:null,
  ready:false,
  committed:false,
  generation:2,
  lastUsed:2
};

const context = {
  assert,
  Date,
  WECOM_CONTACT_WINDOW_LIMIT:3,
  WECOM_VIEWPORT_COMMIT_MAX:8,
  LOCAL_DEV_MODE:true,
  state:{ selectedPointId:'contact-b' },
  weComContactWindows:new Map([
    ['contact-a', oldWindow],
    ['contact-b', newWindow]
  ]),
  weComSegmentFrameRegistry:new Map(),
  weComCommittedContactPointId:'contact-a',
  weComPreparingContactPointId:'contact-b',
  weComTimelineViewer:null,
  document:{},
  $:id => {
    assert.equal(id, 'thread');
    return thread;
  },
  bindThreadInteractions() {},
  selectedContact:() => ({ channels:['wecom', 'email'] }),
  weComLayoutMode:() => 'mixed',
  resizeWeComStandaloneFrames() {},
  setWeComTitleLoading() {},
  mountWeComTimelineMessages:async () => [],
  requestAnimationFrame:callback => callback(),
  toast() {}
};
context.globalThis = context;
vm.createContext(context);
vm.runInContext(windowSource, context);

assert.equal(context.commitWeComContactWindow('contact-b', newWindow), false);
assert.deepEqual(thread.children, oldRows,
  'a cold contact must not replace the old panel before the first viewport is ready');

newWindow.ready = true;
assert.equal(context.commitWeComContactWindow('contact-b', newWindow), true);
assert.strictEqual(oldWindow.container.children[0], oldRows[0],
  'the previous contact rows must move into its cache without cloning');
assert.strictEqual(thread.children[0], newRows[0],
  'the prepared contact rows must move into the visible thread without cloning');
assert.equal(context.weComCommittedContactPointId, 'contact-b');
assert.equal(context.weComPreparingContactPointId, '');

console.log('wecom contact window behavior ok');
