import { describe, expect, it } from 'vitest';
import { router } from './router';

describe('canonical conversation routes', () => {
  it('declares contact and group workspace routes', () => {
    const routes = router.routes[1].children ?? [];
    const paths = routes.map((route) => route.path);
    expect(paths).toContain('conversations/contact/:contactId');
    expect(paths).toContain('conversations/wecom-group/:sourceConversationId');
    expect(paths).toContain('thread/:contactId');
  });
});
