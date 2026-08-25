export type WeComViewerStage =
  | 'viewer-token'
  | 'sdk-load'
  | 'sdk-init'
  | 'session-create'
  | 'session-load'
  | 'frame-create'
  | 'frame-mount'
  | 'frame-update';

export class WeComViewerError extends Error {
  readonly stage: WeComViewerStage;
  readonly status?: number;
  readonly code?: string;

  constructor(stage: WeComViewerStage, reason: string, status?: number, code?: string) {
    super(reason);
    this.name = 'WeComViewerError';
    this.stage = stage;
    this.status = status;
    this.code = code;
  }
}

export function formatWeComViewerError(error: unknown): string {
  if (!(error instanceof WeComViewerError)) return '未知阶段：企业微信组件初始化失败';
  const metadata = [error.status, error.code].filter((value) => value !== undefined).join('，');
  return `${error.stage}：${error.message}${metadata ? `（${metadata}）` : ''}`;
}

export function asWeComViewerError(error: unknown, stage: WeComViewerStage): WeComViewerError {
  if (error instanceof WeComViewerError) return error;
  return new WeComViewerError(stage, error instanceof Error ? error.message : '企业微信组件初始化失败');
}
