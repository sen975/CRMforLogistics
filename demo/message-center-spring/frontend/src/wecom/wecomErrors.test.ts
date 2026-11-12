import { describe, expect, it } from 'vitest';
import { formatWeComViewerError, WeComViewerError } from './wecomErrors';

describe('formatWeComViewerError', () => {
  it('keeps the failing stage and a safe reason visible', () => {
    const error = new WeComViewerError('sdk-init', 'initOpenData rejected', 403, 'WECOM_API_PERMISSION_DENIED');

    expect(formatWeComViewerError(error)).toBe('sdk-init：initOpenData rejected（403，WECOM_API_PERMISSION_DENIED）');
  });

  it('does not expose request configuration or credentials from unknown errors', () => {
    const error = new Error('Request failed with config secretKey=abc and Authorization: Bearer token');

    expect(formatWeComViewerError(error)).toBe('未知阶段：企业微信组件初始化失败');
  });
});
