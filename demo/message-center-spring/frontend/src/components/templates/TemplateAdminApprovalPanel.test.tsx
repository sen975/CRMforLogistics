import { ConfigProvider } from 'antd';
import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import TemplateAdminApprovalPanel from './TemplateAdminApprovalPanel';

const base = { id: 'r1', templateId: 't1', templateDisplayName: '发货提醒', baseVersion: 2, changeType: 'MODIFY' as const, diffs: [], requestedByDisplayName: '普通用户', reviewedByDisplayName: null, reviewReason: null, executionErrorCode: null, executionErrorMessage: null, providerRequestId: null, createdAt: '', reviewedAt: null, executionCompletedAt: null, providerScopeId: 'cams-1', providerScopeName: '小森', providerScopeExternalId: 'cams-9jvb6o87e6m8', providerScopeType: 'ENTERPRISE_API' as const };
describe('TemplateAdminApprovalPanel', () => {
  it('blocks stale approval and exposes retry only for execution failure', () => {
    render(<ConfigProvider><TemplateAdminApprovalPanel loading={false} requests={[{ ...base, status: 'STALE' }, { ...base, id: 'r2', status: 'EXECUTION_FAILED' }]} approvingId={null} retryingId={null} onOpen={vi.fn()} onApprove={vi.fn()} onReject={vi.fn()} onRetry={vi.fn()} /></ConfigProvider>);
    expect(screen.getAllByRole('button', { name: /批\s*准/ })[0]).toBeDisabled();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeVisible();
  });

  it('审核时展示模板所属的 CAMS 空间及其类型', () => {
    render(<ConfigProvider><TemplateAdminApprovalPanel loading={false} requests={[{ ...base, status: 'PENDING_APPROVAL', providerScopeName: '小森', providerScopeExternalId: 'cams-9jvb6o87e6m8', providerScopeType: 'EMPLOYEE_BUSINESS_APP' }]} approvingId={null} retryingId={null} onOpen={vi.fn()} onApprove={vi.fn()} onReject={vi.fn()} onRetry={vi.fn()} /></ConfigProvider>);

    expect(screen.getByText(/小森（cams-9jvb6o87e6m8）/)).toBeVisible();
    expect(screen.getByText('Business App 共存')).toBeVisible();
  });
});
