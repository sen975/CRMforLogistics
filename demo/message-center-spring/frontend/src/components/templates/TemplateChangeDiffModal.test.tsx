import { ConfigProvider } from 'antd';
import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import TemplateChangeDiffModal from './TemplateChangeDiffModal';

describe('TemplateChangeDiffModal', () => {
  it('renders structured values without exposing raw JSON', () => {
    render(<ConfigProvider><TemplateChangeDiffModal open onClose={() => {}} request={{ id: 'r1', templateId: 't1', templateDisplayName: '发货提醒', baseVersion: 2, changeType: 'MODIFY', status: 'PENDING_APPROVAL', diffs: [{ field: 'components', label: '正文', beforeValue: { text: '旧正文' }, afterValue: { text: '新正文' } }], requestedByDisplayName: '普通用户', reviewedByDisplayName: null, reviewReason: null, executionErrorCode: null, executionErrorMessage: null, providerRequestId: null, createdAt: '', reviewedAt: null, executionCompletedAt: null }} /></ConfigProvider>);
    expect(screen.getByText('正文')).toBeInTheDocument();
    expect(screen.getAllByText(/结构化内容已变更/)).toHaveLength(2);
    expect(screen.queryByText('{"text":"旧正文"}')).not.toBeInTheDocument();
  });
});
