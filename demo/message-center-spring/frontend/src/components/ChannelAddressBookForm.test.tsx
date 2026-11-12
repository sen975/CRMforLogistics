import '@testing-library/jest-dom/vitest';
import { ConfigProvider } from 'antd';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChannelAddressBookForm from './ChannelAddressBookForm';

function renderForm(channel: 'chatapp' | 'email' | 'phone', onSubmit = vi.fn()) {
  render(
    <ConfigProvider>
      <ChannelAddressBookForm channel={channel} open loading={false} onCancel={vi.fn()} onSubmit={onSubmit} />
    </ConfigProvider>,
  );
  return onSubmit;
}

describe('ChannelAddressBookForm', () => {
  it.each([
    ['chatapp', '新增WhatsApp联系人', '号码', '例如：+8613812345678'],
    ['email', '新增邮件联系人', '邮箱', '例如：name@example.com'],
    ['phone', '新增电话联系人', '号码', '例如：13812345678'],
  ] as const)('renders the %s manual-contact fields', (channel, title, addressLabel, placeholder) => {
    renderForm(channel);
    expect(screen.getByText(title)).toBeInTheDocument();
    expect(screen.getByLabelText(addressLabel)).toHaveAttribute('placeholder', placeholder);
  });

  it('trims both display name and address before submitting', async () => {
    const onSubmit = renderForm('email');
    fireEvent.change(screen.getByLabelText('显示名称'), { target: { value: '  张经理  ' } });
    fireEvent.change(screen.getByLabelText('邮箱'), { target: { value: '  manager@example.com  ' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({ displayName: '张经理', address: 'manager@example.com' }));
  });
});
