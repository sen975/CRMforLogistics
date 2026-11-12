import '@testing-library/jest-dom/vitest';
import { ConfigProvider } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import WeComAppChatPanel from './WeComAppChatPanel';

describe('WeComAppChatPanel', () => {
  it('exposes a chat id query control', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={queryClient}><ConfigProvider><WeComAppChatPanel authCorpId="corp-1" /></ConfigProvider></QueryClientProvider>);
    expect(screen.getByLabelText('应用群聊 Chat ID')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '查询群聊' })).toBeInTheDocument();
  });
});
