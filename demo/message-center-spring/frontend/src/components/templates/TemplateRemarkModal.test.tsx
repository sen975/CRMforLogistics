import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import TemplateRemarkModal from './TemplateRemarkModal';
import { updateAdminTemplateRemark } from '../../api/endpoints';

const client = vi.hoisted(() => ({ put: vi.fn() }));

vi.mock('../../api/client', () => ({ default: client }));

beforeEach(() => {
  vi.clearAllMocks();
  client.put.mockResolvedValue({ data: { templateCode: 'tpl-1' } });
});

describe('TemplateRemarkModal', () => {
  it('uses the dedicated account-scoped remark endpoint contract', async () => {
    await updateAdminTemplateRemark('account-1', 'tpl-1', 'zh_CN', '发货提醒');

    expect(client.put).toHaveBeenCalledWith(
      '/v1/channel-accounts/account-1/whatsapp/templates/tpl-1/remark',
      { remark: '发货提醒' },
      { params: { language: 'zh_CN' } },
    );
  });

  it('keeps the official name read-only and saves only the bounded local remark', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(
      <TemplateRemarkModal
        open
        officialName="order_ready"
        remark="发货提醒"
        saving={false}
        onCancel={vi.fn()}
        onSave={onSave}
      />,
    );

    expect(screen.getByText('order_ready')).toBeInTheDocument();
    expect(screen.queryByDisplayValue('order_ready')).not.toBeInTheDocument();
    const input = screen.getByRole('textbox', { name: '模板备注' });
    expect(input).toHaveAttribute('maxlength', '120');

    await user.clear(input);
    await user.type(input, '仓库发货提醒');
    await user.click(screen.getByRole('button', { name: '保存备注' }));

    await waitFor(() => expect(onSave).toHaveBeenCalledWith('仓库发货提醒'));
    expect(screen.queryByRole('button', { name: /送审/ })).not.toBeInTheDocument();
  });
});
