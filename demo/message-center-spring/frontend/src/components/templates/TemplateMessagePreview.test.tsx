import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import TemplateMessagePreview from './TemplateMessagePreview';
import type { PublicTemplatePage, PublicTemplateVariable } from '../../api/types';

const variables: PublicTemplateVariable[] = [
  { code: 'name', name: '姓名', example: '张三', format: 'string' },
];

function page(overrides: Partial<PublicTemplatePage> = {}): PublicTemplatePage {
  return {
    name: 'page1',
    text: '您好 $(name)',
    buttons: [],
    ...overrides,
  };
}

describe('TemplateMessagePreview', () => {
  it('renders declared variables and full-mode website buttons as protected external links', () => {
    render(
      <TemplateMessagePreview
        page={page({ buttons: [{ name: '查看', type: 'visitWebsite', url: 'https://example.com' }] })}
        variables={variables}
        mode="parameter"
      />,
    );

    expect(screen.getByText('name')).toHaveClass('template-variable');
    expect(screen.getByRole('link', { name: '查看' })).toHaveAttribute('href', 'https://example.com');
    expect(screen.getByRole('link', { name: '查看' })).toHaveAttribute('target', '_blank');
    expect(screen.getByRole('link', { name: '查看' })).toHaveAttribute('rel', 'noreferrer');
  });

  it('uses declared variable examples in example mode', () => {
    render(<TemplateMessagePreview page={page()} variables={variables} mode="example" />);

    expect(screen.getByText('张三')).toHaveClass('template-variable');
    expect(screen.queryByText('name')).not.toBeInTheDocument();
  });

  it('keeps an accessible message canvas when the page has no body', () => {
    const { container } = render(<TemplateMessagePreview page={page({ text: null })} variables={variables} mode="parameter" />);

    expect(screen.getByRole('article', { name: '模板消息预览' })).toBeInTheDocument();
    expect(container.querySelector('.template-message-text')).toBeEmptyDOMElement();
  });

  it('renders compact actions as non-interactive text inside a fixed-height canvas', () => {
    const { container } = render(
      <TemplateMessagePreview
        compact
        page={page({ buttons: [{ name: '查看', type: 'visitWebsite', url: 'https://example.com' }] })}
        variables={variables}
        mode="parameter"
      />,
    );

    expect(container.querySelector('.template-message-canvas')).toHaveClass('template-message-canvas--compact');
    expect(screen.getByText('查看')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '查看' })).not.toBeInTheDocument();
  });

  it('renders undeclared tokens as plain text instead of variable highlights', () => {
    render(<TemplateMessagePreview page={page({ text: '您好 $(missing)' })} variables={variables} mode="parameter" />);

    expect(screen.getByText('$(missing)')).not.toHaveClass('template-variable');
  });

  it('renders a button without a URL as non-interactive text in full mode', () => {
    render(
      <TemplateMessagePreview
        page={page({ buttons: [{ name: '提交', type: 'submit', url: null }] })}
        variables={variables}
        mode="parameter"
      />,
    );

    expect(screen.getByText('提交')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '提交' })).not.toBeInTheDocument();
  });

  it('renders JavaScript and relative URLs as non-interactive text', () => {
    render(
      <TemplateMessagePreview
        page={page({
          buttons: [
            { name: '执行脚本', type: 'visitWebsite', url: 'javascript:alert(1)' },
            { name: '站内路径', type: 'visitWebsite', url: '/templates/next' },
          ],
        })}
        variables={variables}
        mode="parameter"
      />,
    );

    expect(screen.getByText('执行脚本')).toBeInTheDocument();
    expect(screen.getByText('站内路径')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '执行脚本' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '站内路径' })).not.toBeInTheDocument();
  });

  it('renders the footer as its own row instead of letting it read as body text', () => {
    const { container } = render(
      <TemplateMessagePreview
        page={page({ footer: '如需调整请直接回复本条消息' })}
        variables={variables}
        mode="parameter"
      />,
    );

    expect(container.querySelector('.template-message-footer')).toHaveTextContent('如需调整请直接回复本条消息');
    // 两个节点分开：并进正文里，用户会读成「正文多了一句莫名其妙的话」。
    expect(container.querySelector('.template-message-text')).not.toHaveTextContent('如需调整请直接回复本条消息');
  });

  it('keeps the footer out of the tree when the page declares none', () => {
    const { container } = render(<TemplateMessagePreview page={page()} variables={variables} mode="parameter" />);

    expect(container.querySelector('.template-message-footer')).not.toBeInTheDocument();
  });

  it('shows a footer token verbatim because the platform does not allow variables there', () => {
    const { container } = render(
      <TemplateMessagePreview page={page({ footer: '退订请回 $(name)' })} variables={variables} mode="example" />,
    );

    // 页脚不解析变量：原样显示才看得见「这里写了平台不认的东西」。
    expect(container.querySelector('.template-message-footer')).toHaveTextContent('退订请回 $(name)');
    expect(container.querySelector('.template-message-footer .template-variable')).not.toBeInTheDocument();
  });
});
