import { useEffect, useState } from 'react';
import { Alert, Button, Input, Pagination, Select, Space } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { fetchPublicTemplates } from '../../api/endpoints';
import type { PublicTemplate, PublicTemplateQuery } from '../../api/types';
import { publicTemplateToEditorDraft, type PublicTemplateConversionResult } from './publicTemplateConversion';
import PublicTemplateCardGrid from './PublicTemplateCardGrid';
import PublicTemplateDetailModal from './PublicTemplateDetailModal';
import type { PreviewMode } from './publicTemplatePreview';
import { WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS } from './whatsappLanguages';

function errorDetails(error: unknown): { message: string; traceId?: string } {
  const response = (error as { response?: { data?: { message?: string; traceId?: string } } }).response;
  return { message: response?.data?.message ?? '加载公共模板失败，请稍后重试', traceId: response?.data?.traceId };
}

function filterValues(value: string): string[] | undefined {
  const values = value.split(',').map((item) => item.trim()).filter(Boolean).slice(0, 20);
  return values.length > 0 ? values : undefined;
}

export default function PublicTemplateLibrary({
  hasWhatsAppAccount,
  onCreateFromPublicTemplate,
}: {
  hasWhatsAppAccount: boolean;
  onCreateFromPublicTemplate?: (draft: PublicTemplateConversionResult) => void;
}) {
  const [query, setQuery] = useState<PublicTemplateQuery>({ language: 'zh_CN', page: 1, size: 20 });
  const [industryInput, setIndustryInput] = useState('');
  const [usecaseInput, setUsecaseInput] = useState('');
  const [selectedTemplate, setSelectedTemplate] = useState<PublicTemplate | null>(null);
  const [selectedPageIndex, setSelectedPageIndex] = useState<number | null>(null);
  const [previewMode, setPreviewMode] = useState<PreviewMode>('parameter');
  const templatesQuery = useQuery({
    queryKey: ['public-templates', query],
    queryFn: () => fetchPublicTemplates(query),
    enabled: hasWhatsAppAccount,
    retry: false,
  });
  const rows = hasWhatsAppAccount ? templatesQuery.data?.items ?? [] : [];
  const queryError = templatesQuery.error ? errorDetails(templatesQuery.error) : null;
  const setFilter = (patch: Partial<PublicTemplateQuery>) => setQuery((current) => ({ ...current, ...patch, page: 1 }));

  const closeDetail = () => {
    setSelectedTemplate(null);
  };
  const openDetail = (template: PublicTemplate) => {
    if (!hasWhatsAppAccount) return;
    setSelectedTemplate(template);
    setSelectedPageIndex(template.content.pages.length === 1 ? 0 : null);
    setPreviewMode('parameter');
  };

  useEffect(() => {
    setSelectedTemplate(null);
    setSelectedPageIndex(null);
  }, [hasWhatsAppAccount]);

  const customize = () => {
    if (!selectedTemplate || selectedPageIndex === null || !onCreateFromPublicTemplate) return;
    const draft = publicTemplateToEditorDraft(selectedTemplate, selectedPageIndex);
    closeDetail();
    onCreateFromPublicTemplate(draft);
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      {!hasWhatsAppAccount && <Alert type="warning" showIcon message="没有可用的 WhatsApp 账号" />}
      <Space wrap style={{ width: '100%' }}>
        <Input aria-label="公共模板搜索" disabled={!hasWhatsAppAccount} placeholder="搜索公共模板名称" prefix={<SearchOutlined />} allowClear value={query.name ?? ''} onChange={(event) => setFilter({ name: event.target.value || undefined })} style={{ width: 240 }} />
        <Select aria-label="公共模板语言" disabled={!hasWhatsAppAccount} value={query.language} onChange={(value) => setFilter({ language: value })} options={WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS} showSearch optionFilterProp="label" style={{ width: 190 }} />
        <Select aria-label="公共模板类别" disabled={!hasWhatsAppAccount} placeholder="模板类别" allowClear value={query.category} onChange={(value) => setFilter({ category: value })} options={[{ value: 'UTILITY', label: '工具' }, { value: 'MARKETING', label: '营销' }]} style={{ width: 130 }} />
        <Input aria-label="行业筛选" disabled={!hasWhatsAppAccount} placeholder="行业，多个用逗号分隔" allowClear value={industryInput} onChange={(event) => { setIndustryInput(event.target.value); setFilter({ industries: filterValues(event.target.value) }); }} style={{ width: 190 }} />
        <Input aria-label="用途筛选" disabled={!hasWhatsAppAccount} placeholder="用途，多个用逗号分隔" allowClear value={usecaseInput} onChange={(event) => { setUsecaseInput(event.target.value); setFilter({ usecases: filterValues(event.target.value) }); }} style={{ width: 190 }} />
      </Space>
      {queryError && <Alert type="error" showIcon message={queryError.message} description={queryError.traceId ? `追踪 ID：${queryError.traceId}` : undefined} action={<Button size="small" onClick={() => void templatesQuery.refetch()}>重试加载</Button>} />}
      <PublicTemplateCardGrid
        templates={rows}
        loading={hasWhatsAppAccount && (templatesQuery.isLoading || templatesQuery.isFetching)}
        emptyDescription={!hasWhatsAppAccount ? '配置 WhatsApp 账号后即可浏览公共模板' : templatesQuery.error ? '暂无可显示的公共模板' : '暂无公共模板'}
        onOpen={openDetail}
      />
      <Pagination
        current={templatesQuery.data?.page ?? query.page ?? 1}
        pageSize={templatesQuery.data?.size ?? query.size ?? 20}
        total={templatesQuery.data?.total ?? 0}
        showSizeChanger={false}
        disabled={!hasWhatsAppAccount || !templatesQuery.data?.total}
        onChange={(page) => setQuery((current) => ({ ...current, page }))}
      />
      <PublicTemplateDetailModal
        template={selectedTemplate}
        accountName={hasWhatsAppAccount ? '当前 WhatsApp 账号' : ''}
        selectedPageIndex={selectedPageIndex}
        previewMode={previewMode}
        customizeEnabled={!!onCreateFromPublicTemplate}
        onPageChange={setSelectedPageIndex}
        onPreviewModeChange={setPreviewMode}
        onCustomize={customize}
        onClose={closeDetail}
      />
    </Space>
  );
}
