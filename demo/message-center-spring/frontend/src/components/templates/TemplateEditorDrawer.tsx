import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Drawer, Form, Input, Select, Space, Typography } from 'antd';
import type { TemplateAdmin, TemplateButton, TemplateHeaderFormat, TemplateMediaAsset } from '../../api/types';
import { buildCommand, bodyComponent, headerFormatLabels, requestId, variableNames } from './templateUi';

const headerOptions = Object.entries(headerFormatLabels).map(([value, label]) => ({ value, label }));

export default function TemplateEditorDrawer({
  open,
  template,
  submitting,
  uploadMedia,
  onClose,
  onSubmit,
}: {
  open: boolean;
  template: TemplateAdmin | null;
  submitting?: boolean;
  uploadMedia: (format: 'IMAGE' | 'VIDEO' | 'DOCUMENT', file: File) => Promise<TemplateMediaAsset>;
  onClose: () => void;
  onSubmit: (command: ReturnType<typeof buildCommand>, editing: boolean) => Promise<void>;
}) {
  const [name, setName] = useState('');
  const [language, setLanguage] = useState('');
  const [category, setCategory] = useState<'UTILITY' | 'MARKETING'>('UTILITY');
  const [body, setBody] = useState('');
  const [headerFormat, setHeaderFormat] = useState<TemplateHeaderFormat | null>(null);
  const [headerText, setHeaderText] = useState('');
  const [mediaAssetId, setMediaAssetId] = useState<string | null>(null);
  const [footer, setFooter] = useState('');
  const [buttons, setButtons] = useState<TemplateButton[]>([]);
  const [examples, setExamples] = useState<Record<string, string[]>>({});
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const variables = useMemo(() => variableNames(body), [body]);

  useEffect(() => {
    if (!open) return;
    const header = template?.components.find((component) => component.type === 'HEADER');
    const bodyItem = bodyComponent(template?.components ?? []);
    const footerItem = template?.components.find((component) => component.type === 'FOOTER');
    const buttonItem = template?.components.find((component) => component.type === 'BUTTONS');
    setName(template?.name ?? '');
    setLanguage(template?.language ?? '');
    setCategory(template?.category === 'MARKETING' ? 'MARKETING' : 'UTILITY');
    setBody(bodyItem.text ?? '');
    setHeaderFormat(header?.headerFormat ?? null);
    setHeaderText(header?.text ?? '');
    setMediaAssetId(header?.mediaAssetId ?? null);
    setFooter(footerItem?.text ?? '');
    setButtons(buttonItem?.buttons ?? []);
    setExamples(template?.examples ?? {});
    setUploadError(null);
  }, [open, template]);

  useEffect(() => {
    setExamples((current) => {
      const next: Record<string, string[]> = {};
      for (const variable of variables) next[variable] = current[variable] ?? [''];
      return next;
    });
  }, [variables]);

  async function handleUpload(file: File) {
    if (!headerFormat || headerFormat === 'TEXT') return;
    setUploading(true);
    setUploadError(null);
    try {
      const asset = await uploadMedia(headerFormat, file);
      setMediaAssetId(asset.id);
    } catch (error) {
      const response = (error as { response?: { data?: { message?: string } } }).response;
      setUploadError(response?.data?.message ?? '素材上传失败');
    } finally {
      setUploading(false);
    }
  }

  async function submit() {
    await onSubmit(buildCommand({ name, language, category, body, headerFormat, headerText, mediaAssetId, footer, buttons, examples }), !!template);
  }

  return (
    <Drawer title={template ? '编辑模板' : '新建模板'} open={open} onClose={onClose} width="min(680px, 100vw)" destroyOnClose footer={<Space><Button onClick={onClose}>取消</Button><Button type="primary" loading={submitting} onClick={submit}>{template ? '提交修改' : '提交创建'}</Button></Space>}>
      <Form layout="vertical">
        <Form.Item label="模板名称" required><Input aria-label="模板名称" value={name} onChange={(event) => setName(event.target.value)} /></Form.Item>
        <Form.Item label="语言" required><Input aria-label="语言" value={language} onChange={(event) => setLanguage(event.target.value)} placeholder="例如 zh_CN" /></Form.Item>
        <Form.Item label="模板类别" required><Select aria-label="模板类别" value={category} onChange={setCategory} options={[{ value: 'UTILITY', label: '工具' }, { value: 'MARKETING', label: '营销' }]} /></Form.Item>
        <Form.Item label="正文" required><Input.TextArea aria-label="正文" value={body} onChange={(event) => setBody(event.target.value)} rows={5} /></Form.Item>
        {variables.length > 0 && <section style={{ marginBottom: 16 }}><Typography.Title level={5}>变量示例</Typography.Title>{variables.map((variable) => <Form.Item key={variable} label={`${variable} 示例`}><Input aria-label={`${variable} 示例`} value={examples[variable]?.[0] ?? ''} onChange={(event) => setExamples((current) => ({ ...current, [variable]: [event.target.value] }))} /></Form.Item>)}</section>}
        <Form.Item label="Header 类型"><Select aria-label="Header 类型" allowClear value={headerFormat ?? undefined} onChange={(value: TemplateHeaderFormat | undefined) => { setHeaderFormat(value ?? null); setMediaAssetId(null); }} options={headerOptions} /></Form.Item>
        {headerFormat === 'TEXT' && <Form.Item label="Header 文本"><Input value={headerText} onChange={(event) => setHeaderText(event.target.value)} /></Form.Item>}
        {headerFormat && headerFormat !== 'TEXT' && <Form.Item label="Header 素材"><Input aria-label="Header 素材" type="file" accept={headerFormat === 'IMAGE' ? 'image/jpeg,image/png' : headerFormat === 'VIDEO' ? 'video/mp4' : 'application/pdf'} onChange={(event) => { const file = event.target.files?.[0]; if (file) void handleUpload(file); }} />{uploading && <Alert style={{ marginTop: 8 }} type="info" message="正在上传素材" showIcon />}{uploadError && <Alert style={{ marginTop: 8 }} type="error" message={uploadError} showIcon />}{mediaAssetId && !uploading && <Typography.Text type="success">素材 ID：{mediaAssetId}</Typography.Text>}</Form.Item>}
        <Form.Item label="Footer"><Input value={footer} onChange={(event) => setFooter(event.target.value)} /></Form.Item>
        <Form.Item label="按钮"><Space direction="vertical" style={{ width: '100%' }}>{buttons.map((button, index) => <Space key={`${button.type}-${index}`} style={{ width: '100%' }}><Select aria-label={`按钮 ${index + 1} 类型`} value={button.type} options={[{ value: 'QUICK_REPLY', label: '快速回复' }, { value: 'URL', label: '网址' }, { value: 'PHONE_NUMBER', label: '电话' }]} onChange={(type) => setButtons((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, type } : item))} /><Input aria-label={`按钮 ${index + 1} 文案`} value={button.text ?? ''} onChange={(event) => setButtons((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, text: event.target.value } : item))} /></Space>)}<Button onClick={() => setButtons((current) => [...current, { type: 'QUICK_REPLY', text: '', url: null, phoneNumber: null }])}>添加按钮</Button></Space></Form.Item>
      </Form>
    </Drawer>
  );
}
