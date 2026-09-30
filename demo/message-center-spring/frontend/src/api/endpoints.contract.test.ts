import { describe, expect, it } from 'vitest';
import * as endpoints from './endpoints';

describe('WhatsApp template API surface', () => {
  it('does not expose obsolete account-admin CRUD wrappers on the shared template path', () => {
    expect(endpoints).not.toHaveProperty('fetchAdminTemplates');
    expect(endpoints).not.toHaveProperty('fetchAdminTemplate');
    expect(endpoints).not.toHaveProperty('createAdminTemplate');
    expect(endpoints).not.toHaveProperty('updateAdminTemplate');
    expect(endpoints).not.toHaveProperty('updateAdminTemplateRemark');
    expect(endpoints).not.toHaveProperty('setAdminTemplateSendPermission');
    expect(endpoints).not.toHaveProperty('deleteAdminTemplate');
    expect(endpoints).not.toHaveProperty('syncAdminTemplates');
  });
});
