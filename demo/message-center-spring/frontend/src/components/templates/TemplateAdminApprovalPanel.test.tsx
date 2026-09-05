import { ConfigProvider } from 'antd';
import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import TemplateAdminApprovalPanel from './TemplateAdminApprovalPanel';

const base = { id: 'r1', templateId: 't1', templateDisplayName: '发货提醒', baseVersion: 2, changeType: 'MODIFY' as const, diffs: [], requestedByDisplayName: '普通用户', reviewedByDisplayName: null, reviewReason: null, executionErrorCode: null, executionErrorMessage: null, providerRequestId: null, createdAt: '', reviewedAt: null, executionCompletedAt: null };
describe('TemplateAdminApprovalPanel', () => {
  it('blocks stale approval and exposes retry only for execution failure', () => {
    render(<ConfigProvider><TemplateAdminApprovalPanel loading={false} requests={[{ ...base, status: 'STALE' }, { ...base, id: 'r2', status: 'EXECUTION_FAILED' }]} approvingId={null} retryingId={null} onOpen={vi.fn()} onApprove={vi.fn()} onReject={vi.fn()} onRetry={vi.fn()} /></ConfigProvider>);
    expect(screen.getAllByRole('button', { name: /批\s*准/ })[0]).toBeDisabled();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeVisible();
  });
});
