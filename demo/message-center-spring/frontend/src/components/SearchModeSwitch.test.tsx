import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import SearchModeSwitch from './SearchModeSwitch';

describe('SearchModeSwitch', () => {
  it('shows the current mode and reports the picked one', async () => {
    const onChange = vi.fn();
    render(<SearchModeSwitch value="contact" onChange={onChange} />);

    expect(screen.getByRole('button', { name: '搜索模式' })).toHaveTextContent('联系人');

    await userEvent.click(screen.getByRole('button', { name: '搜索模式' }));
    await userEvent.click(await screen.findByRole('menuitem', { name: '标签' }));

    expect(onChange).toHaveBeenCalledWith('tag');
  });
});
