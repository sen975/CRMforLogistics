import { describe, expect, it } from 'vitest';
import type { PublicTemplateVariable } from '../../api/types';
import { previewSegments } from './publicTemplatePreview';

const variables: PublicTemplateVariable[] = [
  { code: 'name', name: '姓名', example: '张三', format: 'string' },
  { code: 'order', name: '订单', example: 'A-100', format: 'string' },
];

describe('previewSegments', () => {
  it('按参数模式解析 ChatApp 模板变量', () => {
    expect(previewSegments('您好 $(name)，订单 $(order)', variables, 'parameter')).toEqual([
      { kind: 'text', value: '您好 ' },
      { kind: 'variable', code: 'name', value: 'name', resolved: true },
      { kind: 'text', value: '，订单 ' },
      { kind: 'variable', code: 'order', value: 'order', resolved: true },
    ]);
  });

  it('按示例模式回退参数名并保留未声明 token 原文', () => {
    expect(previewSegments('$(name) $(order) $(missing)', [
      variables[0],
      { ...variables[1], example: '  ' },
    ], 'example')).toEqual([
      { kind: 'variable', code: 'name', value: '张三', resolved: true },
      { kind: 'text', value: ' ' },
      { kind: 'variable', code: 'order', value: 'order', resolved: true },
      { kind: 'text', value: ' ' },
      { kind: 'variable', code: 'missing', value: '$(missing)', resolved: false },
    ]);
  });

  it('不把无效的花括号语法识别为模板变量', () => {
    expect(previewSegments('旧格式 {{name}} ${order}', variables, 'parameter')).toEqual([
      { kind: 'text', value: '旧格式 {{name}} ${order}' },
    ]);
  });
});
