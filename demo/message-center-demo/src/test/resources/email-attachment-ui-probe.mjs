const MAX_COUNT = 16;
const MAX_TOTAL = 20 * 1024 * 1024;
const state = { emailAttachments: [{ name: 'a.txt', size: 3 }, { name: 'b.pdf', size: 4 }] };
const removeEmailAttachment = index => state.emailAttachments.splice(index, 1);
const valid = () => state.emailAttachments.length <= MAX_COUNT
  && state.emailAttachments.reduce((sum, file) => sum + file.size, 0) <= MAX_TOTAL;

removeEmailAttachment(0);
if (state.emailAttachments.length !== 1 || state.emailAttachments[0].name !== 'b.pdf') {
  throw new Error('remove attachment contract failed');
}
state.emailAttachments = Array.from({ length: MAX_COUNT + 1 }, (_, index) => ({ name: `file-${index}`, size: 1 }));
if (valid()) throw new Error('count validation contract failed');
state.emailAttachments = [{ name: 'too-large.bin', size: MAX_TOTAL + 1 }];
if (valid()) throw new Error('size validation contract failed');
console.log('email attachment UI probe passed');
