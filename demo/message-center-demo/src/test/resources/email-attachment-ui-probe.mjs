import assert from 'node:assert/strict';
import fs from 'node:fs';

const html = fs.readFileSync(process.argv[2], 'utf8');
assert.match(html, /id="emailAttachments" type="file" multiple/);
assert.match(html, /state\.emailAttachments = state\.emailAttachments\.concat/);
assert.match(html, /state\.emailAttachments\.length > 16/);
assert.match(html, /totalBytes > 20971520/);
assert.match(html, /state\.emailAttachments\.forEach\(file => form\.append\('file', file, file\.name\)\)/);
assert.match(html, /await fetch\('\/api\/send\/email', \{ method:'POST', body:form \}\)/);
assert.match(html, /headers: \{ 'X-WeCom-Viewer-Auth': auth\.viewerAuthToken \}/);
assert.match(html, /state\.emailAttachments = \[\];/);
assert.match(html, /state\.emailAttachments\.splice/);

const MAX_COUNT = 16;
const MAX_TOTAL = 20 * 1024 * 1024;
const state = { emailAttachments: [{ name: 'a.txt', size: 3 }, { name: 'b.pdf', size: 4 }] };
const valid = () => state.emailAttachments.length <= MAX_COUNT
  && state.emailAttachments.reduce((sum, file) => sum + file.size, 0) <= MAX_TOTAL;
state.emailAttachments.splice(0, 1);
assert.deepEqual(state.emailAttachments, [{ name: 'b.pdf', size: 4 }]);
state.emailAttachments = Array.from({ length: MAX_COUNT + 1 }, (_, index) => ({ name: `file-${index}`, size: 1 }));
assert.equal(valid(), false);
state.emailAttachments = [{ name: 'too-large.bin', size: MAX_TOTAL + 1 }];
assert.equal(valid(), false);
console.log('email attachment UI probe passed');
