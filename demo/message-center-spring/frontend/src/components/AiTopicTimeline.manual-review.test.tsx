import '@testing-library/jest-dom/vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import AiTopicManualReviewPanel from './AiTopicManualReviewPanel';
import * as endpoints from '../api/endpoints';

vi.mock('../api/endpoints', async () => {
  const actual = await vi.importActual<typeof import('../api/endpoints')>('../api/endpoints');
  return {
    ...actual,
    fetchManualReviewSources: vi.fn(),
    previewManualReview: vi.fn(),
    applyManualReview: vi.fn(),
  };
});

describe('AiTopicManualReviewPanel', () => {
  it('selects a contact identity, supports select all, previews and applies assignments', async () => {
    vi.mocked(endpoints.fetchManualReviewSources).mockResolvedValue({
      contactId: 'contact-1',
      items: [
        { id: 'source-1', contactIdentityId: 'identity-1', sourceType: 'MESSAGE', channelType: 'email', occurredAt: '2026-09-01T01:00:00Z', direction: 'inbound', subject: '报价', text: '请提供报价', selectable: true },
        { id: 'source-2', contactIdentityId: 'identity-1', sourceType: 'MESSAGE', channelType: 'email', occurredAt: '2026-09-01T02:00:00Z', direction: 'outbound', subject: '报价回复', text: '报价 100 元', selectable: true },
      ],
      hasMore: false,
      wecomExcludedReason: null,
    });
    vi.mocked(endpoints.previewManualReview).mockResolvedValue({
      previewId: 'preview-1', contactId: 'contact-1', sourceFingerprint: 'fingerprint',
      assignments: [{ topicKey: 'topic-1', title: '报价跟进', summary: '已完成报价回复', relevance: .9, sourceIds: ['source-1', 'source-2'] }],
      expectedVersions: { 'topic-1': 2 }, expiresAt: '2026-09-02T01:00:00Z',
    });
    vi.mocked(endpoints.applyManualReview).mockResolvedValue({ previewId: 'preview-1', topicIds: ['topic-1'], applied: true });
    const applied = vi.fn();

    render(<AiTopicManualReviewPanel contactId="contact-1" identities={[
      { id: 'identity-1', channelType: 'email', identityScope: 'email', identityValue: 'a@example.com', displayName: '工作邮箱' },
    ]} topics={[]} onApplied={applied} />);

    fireEvent.click(screen.getByRole('button', { name: '手动整理 Topic' }));
    fireEvent.click(screen.getByRole('button', { name: '查询记录' }));
    await waitFor(() => expect(screen.getByText('报价回复')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('checkbox', { name: '全选当前结果' }));
    expect(screen.getAllByRole('checkbox', { name: /选择记录/ })).toHaveLength(2);
    fireEvent.click(screen.getByRole('button', { name: '生成 AI 预览' }));
    await waitFor(() => expect(screen.getByDisplayValue('报价跟进')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '确认应用' }));

    await waitFor(() => expect(endpoints.applyManualReview).toHaveBeenCalledWith(
      'contact-1', 'preview-1', 'fingerprint', expect.any(Array),
    ));
    expect(applied).toHaveBeenCalledOnce();
  });

  it('shows why unavailable WeCom records cannot be selected', async () => {
    vi.mocked(endpoints.fetchManualReviewSources).mockResolvedValue({
      contactId: 'contact-1', items: [], hasMore: false,
      wecomExcludedReason: 'WECOM_SUMMARY_NOT_COMPLETED',
    });
    render(<AiTopicManualReviewPanel contactId="contact-1" identities={[
      { id: 'identity-1', channelType: 'wecom', identityScope: 'wecom', identityValue: 'external-1', displayName: '企业微信客户' },
    ]} topics={[]} onApplied={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: '手动整理 Topic' }));
    fireEvent.click(screen.getByRole('button', { name: '查询记录' }));
    expect(await screen.findByText('企业微信摘要尚未完成，暂不能用于 Topic 整理')).toBeInTheDocument();
  });

  it('allows WhatsApp sources without showing a WeCom summary warning', async () => {
    vi.mocked(endpoints.fetchManualReviewSources).mockResolvedValue({
      contactId: 'contact-1',
      items: [{ id: 'source-1', contactIdentityId: 'identity-1', sourceType: 'MESSAGE', channelType: 'whatsapp', occurredAt: '2026-09-01T01:00:00Z', direction: 'inbound', subject: '', text: 'WhatsApp 原文', selectable: true }],
      hasMore: false,
      wecomExcludedReason: 'WECOM_SUMMARY_NOT_COMPLETED',
    });
    render(<AiTopicManualReviewPanel contactId="contact-1" identities={[
      { id: 'identity-1', channelType: 'whatsapp', identityScope: 'whatsapp', identityValue: '+8613800000000', displayName: 'WhatsApp 客户' },
    ]} topics={[]} onApplied={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: '手动整理 Topic' }));
    fireEvent.click(screen.getByRole('button', { name: '查询记录' }));

    expect(await screen.findByText('WhatsApp 原文')).toBeInTheDocument();
    expect(screen.queryByText('企业微信摘要尚未完成，暂不能用于 Topic 整理')).not.toBeInTheDocument();
  });

  it('does not describe a locked email record as a pending WeCom summary', async () => {
    vi.mocked(endpoints.fetchManualReviewSources).mockResolvedValue({
      contactId: 'contact-1',
      items: [{ id: 'source-1', contactIdentityId: 'identity-1', sourceType: 'MESSAGE', channelType: 'email', occurredAt: '2026-09-01T01:00:00Z', direction: 'inbound', subject: '提单材料', text: '请核对附件', selectable: false, excludedReason: 'TOPIC_REVIEW_SOURCE_LOCKED' }],
      hasMore: false,
      wecomExcludedReason: null,
    });
    render(<AiTopicManualReviewPanel contactId="contact-1" identities={[
      { id: 'identity-1', channelType: 'email', identityScope: 'email', identityValue: 'a@example.com', displayName: '工作邮箱' },
    ]} topics={[]} onApplied={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: '手动整理 Topic' }));
    fireEvent.click(screen.getByRole('button', { name: '查询记录' }));

    expect(await screen.findByText('该记录已被其他 Topic 占用，暂不可选')).toBeInTheDocument();
    expect(screen.queryByText('摘要未完成，暂不可选')).not.toBeInTheDocument();
  });

  it('expands the Topic that owns a locked record inline', async () => {
    vi.mocked(endpoints.fetchManualReviewSources).mockResolvedValue({
      contactId: 'contact-1',
      items: [{ id: 'source-1', contactIdentityId: 'identity-1', sourceType: 'MESSAGE', channelType: 'email', occurredAt: '2026-09-01T01:00:00Z', direction: 'inbound', subject: '提单材料', text: '请核对附件', selectable: false, excludedReason: 'TOPIC_REVIEW_SOURCE_LOCKED', assignedTopicId: 'topic-1', assignedTopicTitle: '华东提单跟进' }],
      hasMore: false,
      wecomExcludedReason: null,
    });
    render(<AiTopicManualReviewPanel contactId="contact-1" identities={[
      { id: 'identity-1', channelType: 'email', identityScope: 'email', identityValue: 'a@example.com', displayName: '工作邮箱' },
    ]} topics={[{
      id: 'topic-1', title: '华东提单跟进', summary: '已确认提单与装箱安排', summarySource: 'AI',
      firstOccurredAt: '2026-08-31T01:00:00Z', lastOccurredAt: '2026-08-31T03:00:00Z',
      channels: ['email'], sourceCount: 4, sourceItems: [], version: 2,
    }]} onApplied={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: '手动整理 Topic' }));
    fireEvent.click(screen.getByRole('button', { name: '查询记录' }));

    expect(await screen.findByText('当前 Topic：')).toBeInTheDocument();
    const topicButton = screen.getByRole('button', { name: '华东提单跟进' });
    expect(topicButton).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(topicButton);
    expect(await screen.findByTestId('assigned-topic-topic-1')).toHaveTextContent('已确认提单与装箱安排');
    expect(screen.getByTestId('assigned-topic-topic-1')).toHaveTextContent('4 条来源');
    expect(topicButton).toHaveAttribute('aria-expanded', 'true');
  });
});
