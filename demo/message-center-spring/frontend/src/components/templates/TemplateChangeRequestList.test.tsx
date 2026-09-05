import { ConfigProvider } from 'antd';
import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import TemplateChangeRequestList from './TemplateChangeRequestList';

describe('TemplateChangeRequestList', () => {
  it('uses the shared status vocabulary', () => {
    render(<ConfigProvider><TemplateChangeRequestList loading={false} onOpen={vi.fn()} requests={[{ id: 'r1', templateId: 't1', templateDisplayName: '发货提醒', baseVersion: 2, changeType: 'MODIFY', status: 'EXECUTION_FAILED', diffs: [], requestedByDisplayName: '普通用户', reviewedByDisplayName: null, reviewReason: null, executionErrorCode: 'UPSTREAM', executionErrorMessage: '调用失败', providerRequestId: null, createdAt: '', reviewedAt: null, executionCompletedAt: null }]} /></ConfigProvider>);
    expect(screen.getByText('执行失败')).toBeInTheDocument();
  });
});
