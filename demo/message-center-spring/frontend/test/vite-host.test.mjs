import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const viteConfig = readFileSync(
  new URL('../vite.config.ts', import.meta.url),
  'utf8',
);

test('development server binds the loopback IPv4 host', () => {
  assert.match(viteConfig, /host:\s*['"]127\.0\.0\.1['"]/);
});
