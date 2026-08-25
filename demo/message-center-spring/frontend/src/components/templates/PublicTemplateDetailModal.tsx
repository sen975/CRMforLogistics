import { Alert, Button, Descriptions, Modal, Segmented, Select, Space } from 'antd';
import type { PublicTemplate } from '../../api/types';
import type { PreviewMode } from './publicTemplatePreview';
import TemplateMessagePreview from './TemplateMessagePreview';

export interface PublicTemplateDetailModalProps {
  template: PublicTemplate | null;
  accountName: string;
  selectedPageIndex: number | null;
  previewMode: PreviewMode;
  customizeEnabled: boolean;
  onPageChange(index: number): void;
  onPreviewModeChange(mode: PreviewMode): void;
  onCustomize(): void;
  onClose(): void;
}

function categoryLabel(category: string | null): string {
  if (category === 'UTILITY') return '工具';
  if (category === 'MARKETING') return '营销';
  return category ?? '未提供';
}

export default function PublicTemplateDetailModal({
  template,
  accountName,
  selectedPageIndex,
  previewMode,
  customizeEnabled,
  onPageChange,
  onPreviewModeChange,
  onCustomize,
  onClose,
}: PublicTemplateDetailModalProps) {
  const previewPage = template?.content.pages[selectedPageIndex ?? 0] ?? { name: null, text: null, buttons: [] };
  const hasNoPages = (template?.content.pages.length ?? 0) === 0;
  const requiresPageChoice = (template?.content.pages.length ?? 0) > 1 && selectedPageIndex === null;

  return (
    <Modal
      title={template?.name}
      open={!!template}
      width="min(1120px, calc(100vw - 32px))"
      destroyOnHidden
      onCancel={onClose}
      footer={template ? (
        <Space wrap>
          <Button onClick={onClose}>取消</Button>
          <Button type="primary" disabled={hasNoPages || requiresPageChoice || !customizeEnabled} onClick={onCustomize}>基于此模板创建</Button>
        </Space>
      ) : null}
    >
      {template && (
        <div className="public-template-workbench-modal-body">
          <section className="public-template-detail-fields">
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="模板代码">{template.code}</Descriptions.Item>
              <Descriptions.Item label="语言">{template.language}</Descriptions.Item>
              <Descriptions.Item label="类别">{categoryLabel(template.category)}</Descriptions.Item>
              <Descriptions.Item label="目标账号">{accountName || '未选择有效 WhatsApp 账号'}</Descriptions.Item>
            </Descriptions>
            {template.content.pages.length > 1 && (
              <label className="public-template-detail-field">
                <span>创建来源页面</span>
                <Select
                  aria-label="创建来源页面"
                  value={selectedPageIndex ?? undefined}
                  placeholder="选择页面"
                  options={template.content.pages.map((page, index) => ({ value: index, label: page.name?.trim() || `页面 ${index + 1}` }))}
                  onChange={onPageChange}
                />
              </label>
            )}
            {requiresPageChoice && <Alert type="info" showIcon message="请选择创建来源页面" style={{ marginBottom: 16 }} />}
            {hasNoPages && <Alert type="warning" showIcon message="公共模板没有可用于创建的页面" style={{ marginBottom: 16 }} />}
          </section>
          <section className="public-template-detail-preview">
            <Segmented
              aria-label="预览模式"
              block
              options={[{ value: 'parameter', label: '参数' }, { value: 'example', label: '示例' }]}
              value={previewMode}
              onChange={(value) => onPreviewModeChange(value as PreviewMode)}
            />
            <TemplateMessagePreview page={previewPage} variables={template.content.variables} mode={previewMode} />
          </section>
        </div>
      )}
    </Modal>
  );
}
