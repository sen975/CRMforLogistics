import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Drawer, Form, Input, Select, Space, Typography } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import type { SharedTemplate, TemplateButton, TemplateCategory, TemplateHeaderFormat, TemplateMediaAsset } from '../../api/types';
import TemplateMessagePreview from './TemplateMessagePreview';
import type { PublicTemplateConversionResult } from './publicTemplateConversion';
import { buildCommand, hasUnsupportedVariableSyntax, headerFormatLabels, initialValueForTemplate, isTemplateLanguage, MAX_TEMPLATE_BODY_LENGTH, MAX_TEMPLATE_NAME_LENGTH, requestId, variableNames } from './templateUi';
import { MediaUploadStateError } from './templateMediaUpload';
import { WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS } from './whatsappLanguages';

const headerOptions = Object.entries(headerFormatLabels).map(([value, label]) => ({ value, label }));

function examplesForVariables(existing: Record<string, string[]>, variables: string[]): Record<string, string[]> {
  const next: Record<string, string[]> = {};
  for (const variable of variables) next[variable] = existing[variable] ?? [''];
  return next;
}

function mediaUploadErrorMessage(error: unknown): string {
  if (error instanceof MediaUploadStateError) {
    if (error.exhausted) return '素材仍在处理中，请稍后重新查询';
    if (error.assetStatus === 'SUBMISSION_UNKNOWN') return '上传结果未知，请勿自动重传';
    return '素材上传失败，请明确选择文件后重新上传';
  }
  const response = (error as { response?: { data?: { message?: string } } }).response;
  return response?.data?.message ?? '素材上传失败';
}

type SourceButtonResolutionAction = 'UNRESOLVED' | 'OMIT' | 'QUICK_REPLY' | 'URL' | 'PHONE_NUMBER';

interface SourceButtonResolution {
  action: SourceButtonResolutionAction;
  text: string;
  url: string;
  phoneNumber: string;
}

function sourceButtonLabel(id: string, name: string | null): string {
  return name?.trim() || id;
}

function initialResolutions(sourceContext: PublicTemplateConversionResult | null | undefined): Record<string, SourceButtonResolution> {
  return Object.fromEntries((sourceContext?.unresolvedButtons ?? []).map(({ id, button }) => [id, {
    action: 'UNRESOLVED',
    text: button.name ?? '',
    url: button.url ?? '',
    phoneNumber: '',
  }]));
}

function sourceResolutionBlockers(resolutions: Record<string, SourceButtonResolution>): string[] {
  return Object.entries(resolutions).flatMap(([id, resolution]) => {
    if (resolution.action === 'UNRESOLVED') return [`源按钮 ${id} 尚未处理`];
    if (resolution.action === 'OMIT') return [];
    if (!resolution.text.trim()) return [`源按钮 ${id} 缺少按钮文案`];
    if (resolution.action === 'URL' && !resolution.url.trim()) return [`源按钮 ${id} 缺少网址`];
    if (resolution.action === 'PHONE_NUMBER' && !resolution.phoneNumber.trim()) return [`源按钮 ${id} 缺少电话号码`];
    return [];
  });
}

function resolvedSourceButtons(resolutions: Record<string, SourceButtonResolution>): TemplateButton[] {
  const buttons: TemplateButton[] = [];
  for (const resolution of Object.values(resolutions)) {
    if (resolution.action === 'OMIT' || resolution.action === 'UNRESOLVED' || !resolution.text.trim()) continue;
    if (resolution.action === 'URL' && resolution.url.trim()) buttons.push({ type: 'URL', text: resolution.text, url: resolution.url, phoneNumber: null });
    else if (resolution.action === 'PHONE_NUMBER' && resolution.phoneNumber.trim()) buttons.push({ type: 'PHONE_NUMBER', text: resolution.text, url: null, phoneNumber: resolution.phoneNumber });
    else if (resolution.action === 'QUICK_REPLY') buttons.push({ type: 'QUICK_REPLY', text: resolution.text, url: null, phoneNumber: null });
  }
  return buttons;
}

function buttonContractBlockers(buttons: TemplateButton[]): string[] {
  const urlCount = buttons.filter((button) => button.type === 'URL').length;
  const phoneCount = buttons.filter((button) => button.type === 'PHONE_NUMBER').length;
  const quickReplyCount = buttons.filter((button) => button.type === 'QUICK_REPLY').length;
  const blockers: string[] = [];
  if (buttons.some((button) => !button.text?.trim())) blockers.push('按钮文案不能为空');
  if (buttons.some((button) => button.type === 'URL' && !button.url?.trim())) blockers.push('网址按钮必须填写网址');
  if (buttons.some((button) => button.type === 'PHONE_NUMBER' && !button.phoneNumber?.trim())) blockers.push('电话按钮必须填写电话号码');
  if (buttons.length > 10) blockers.push('按钮总数不能超过 10 个');
  if (urlCount > 2) blockers.push('网址按钮不能超过 2 个');
  if (phoneCount > 1) blockers.push('电话按钮不能超过 1 个');
  if (quickReplyCount > 0 && (urlCount > 0 || phoneCount > 0)) blockers.push('快速回复不能与网址或电话按钮混用');
  return blockers;
}

export default function TemplateEditorDrawer({
  open,
  template,
  initialValue,
  sourceContext,
  submitting,
  uploadMedia,
  onClose,
  onSubmit,
}: {
  open: boolean;
  template: SharedTemplate | null;
  initialValue?: ReturnType<typeof initialValueForTemplate> | null;
  sourceContext?: PublicTemplateConversionResult | null;
  submitting?: boolean;
  uploadMedia: (format: 'IMAGE' | 'VIDEO' | 'DOCUMENT', file: File, clientRequestId: string, signal: AbortSignal) => Promise<TemplateMediaAsset>;
  onClose: () => void;
  onSubmit: (command: ReturnType<typeof buildCommand>, editing: boolean, remark?: string) => Promise<void>;
}) {
  const [name, setName] = useState('');
  const [remark, setRemark] = useState('');
  const [language, setLanguage] = useState('');
  const [category, setCategory] = useState<TemplateCategory | null>('UTILITY');
  const [body, setBody] = useState('');
  const [headerFormat, setHeaderFormat] = useState<TemplateHeaderFormat | null>(null);
  const [headerText, setHeaderText] = useState('');
  const [mediaAsset, setMediaAsset] = useState<TemplateMediaAsset | null>(null);
  const [mediaAssetId, setMediaAssetId] = useState<string | null>(null);
  const [footer, setFooter] = useState('');
  const [buttons, setButtons] = useState<TemplateButton[]>([]);
  const [examples, setExamples] = useState<Record<string, string[]>>({});
  const [resolutions, setResolutions] = useState<Record<string, SourceButtonResolution>>({});
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const uploadRequestId = useRef<string | null>(null);
  const uploadAbortController = useRef<AbortController | null>(null);
  const skipVariableSync = useRef(false);
  const variables = useMemo(() => [...new Set([
    ...variableNames(body),
    ...(headerFormat === 'TEXT' ? variableNames(headerText) : []),
  ])], [body, headerFormat, headerText]);

  useEffect(() => {
    uploadAbortController.current?.abort();
    uploadAbortController.current = null;
    if (!open) {
      skipVariableSync.current = false;
      uploadRequestId.current = null;
      return;
    }
    const value = initialValue ?? initialValueForTemplate(template);
    setName(value.name);
    setRemark(template?.remark ?? '');
    setLanguage(value.language);
    setCategory(value.category);
    const bodyText = value.body;
    const initialHeaderFormat = value.headerFormat;
    const initialHeaderText = value.headerText;
    const initialVariables = [...new Set([
      ...variableNames(bodyText),
      ...(initialHeaderFormat === 'TEXT' ? variableNames(initialHeaderText) : []),
    ])];
    skipVariableSync.current = true;
    setBody(bodyText);
    setHeaderFormat(initialHeaderFormat);
    setHeaderText(initialHeaderText);
    setMediaAssetId(value.mediaAssetId);
    setMediaAsset(null);
    setFooter(value.footer);
    setButtons(value.buttons);
    setExamples(examplesForVariables(value.examples, initialVariables));
    setResolutions(initialResolutions(sourceContext));
    setUploadError(null);
    uploadRequestId.current = null;
  }, [initialValue, open, sourceContext, template]);

  useEffect(() => () => {
    uploadAbortController.current?.abort();
  }, []);

  useEffect(() => {
    if (!open) return;
    if (skipVariableSync.current) {
      skipVariableSync.current = false;
      return;
    }
    setExamples((current) => examplesForVariables(current, variables));
  }, [open, variables]);

  async function handleUpload(file: File) {
    if (!headerFormat || headerFormat === 'TEXT') return;
    uploadAbortController.current?.abort();
    const controller = new AbortController();
    uploadAbortController.current = controller;
    const clientRequestId = requestId();
    uploadRequestId.current = clientRequestId;
    setUploading(true);
    setUploadError(null);
    setMediaAsset(null);
    setMediaAssetId(null);
    try {
      const asset = await uploadMedia(headerFormat, file, clientRequestId, controller.signal);
      if (uploadRequestId.current !== clientRequestId) return;
      setMediaAsset(asset);
      setMediaAssetId(asset.id);
    } catch (error) {
      if (uploadRequestId.current !== clientRequestId) return;
      if ((error as { name?: string }).name === 'AbortError') return;
      setUploadError(mediaUploadErrorMessage(error));
    } finally {
      if (uploadRequestId.current === clientRequestId) setUploading(false);
    }
  }

  function clearMediaUpload() {
    uploadAbortController.current?.abort();
    uploadAbortController.current = null;
    uploadRequestId.current = null;
    setMediaAsset(null);
    setMediaAssetId(null);
    setUploadError(null);
  }

  const effectiveName = template?.name ?? name;
  const sourceButtons = resolvedSourceButtons(resolutions);
  const blockers = [
    !effectiveName.trim() && '模板名称不能为空',
    effectiveName.length > MAX_TEMPLATE_NAME_LENGTH && '模板名称不能超过 512 个字符',
    !isTemplateLanguage(language) && '请选择支持的语言',
    category === null && '请选择支持的模板类别',
    !body.trim() && '正文不能为空',
    body.length > MAX_TEMPLATE_BODY_LENGTH && '正文不能超过 1024 个字符',
    hasUnsupportedVariableSyntax(body) && '变量只能使用 $(name) 格式',
    headerFormat === 'TEXT' && hasUnsupportedVariableSyntax(headerText) && '变量只能使用 $(name) 格式',
    hasUnsupportedVariableSyntax(footer) && '变量只能使用 $(name) 格式',
    ...sourceResolutionBlockers(resolutions),
    ...buttonContractBlockers([...buttons, ...sourceButtons]),
  ].filter((blocker): blocker is string => Boolean(blocker));

  async function submit() {
    if (blockers.length > 0 || category === null) return;
    await onSubmit(buildCommand({ name: effectiveName, language, category, body, headerFormat, headerText, mediaAssetId, footer, buttons: [...buttons, ...sourceButtons], examples }), !!template, template ? remark : undefined);
  }

  function handleClose() {
    clearMediaUpload();
    onClose();
  }

  const form = <Form layout="vertical">
        <Form.Item label="模板名称" required>{template ? <Typography.Text aria-label="模板名称" strong>{effectiveName}</Typography.Text> : <Input aria-label="模板名称" value={effectiveName} onChange={(event) => setName(event.target.value)} />}</Form.Item>
        {template && <Form.Item label="业务备注"><Input.TextArea aria-label="业务备注" value={remark} onChange={(event) => setRemark(event.target.value)} rows={2} placeholder="仅用于系统内识别，不会提交给 WhatsApp" /></Form.Item>}
        <Form.Item label="语言" required><Select aria-label="语言" value={language || undefined} onChange={setLanguage} options={WHATSAPP_TEMPLATE_LANGUAGE_OPTIONS} placeholder="选择语言" showSearch optionFilterProp="label" /></Form.Item>
        <Form.Item label="模板类别" required><Select aria-label="模板类别" value={category ?? undefined} onChange={setCategory} options={[{ value: 'UTILITY', label: '工具' }, { value: 'MARKETING', label: '营销' }]} placeholder="选择模板类别" /></Form.Item>
        <Form.Item label="正文" required><Input.TextArea aria-label="正文" value={body} onChange={(event) => setBody(event.target.value)} rows={5} /></Form.Item>
        {variables.length > 0 && <section style={{ marginBottom: 16 }}><Typography.Title level={5}>变量示例</Typography.Title>{variables.map((variable) => <Form.Item key={variable} label={`${variable} 示例`}><Input aria-label={`${variable} 示例`} value={examples[variable]?.[0] ?? ''} onChange={(event) => setExamples((current) => ({ ...current, [variable]: [event.target.value] }))} /></Form.Item>)}</section>}
        <Form.Item label="Header 类型"><Select aria-label="Header 类型" allowClear value={headerFormat ?? undefined} onChange={(value: TemplateHeaderFormat | undefined) => { clearMediaUpload(); setHeaderFormat(value ?? null); }} options={headerOptions} /></Form.Item>
        {headerFormat === 'TEXT' && <Form.Item label="Header 文本"><Input aria-label="Header 文本" value={headerText} onChange={(event) => setHeaderText(event.target.value)} /></Form.Item>}
        {headerFormat && headerFormat !== 'TEXT' && <Form.Item label="Header 素材">{mediaAsset || mediaAssetId ? <Space direction="vertical"><Typography.Text type="success">素材 ID：{mediaAssetId}</Typography.Text>{mediaAsset?.format === 'IMAGE' && mediaAsset.providerUrl && <img src={mediaAsset.providerUrl} alt="已上传 Header 素材" style={{ maxWidth: 240, maxHeight: 160 }} />}{mediaAsset?.format === 'VIDEO' && mediaAsset.providerUrl && <video aria-label="已上传 Header 素材" controls src={mediaAsset.providerUrl} style={{ maxWidth: 240 }} />}{mediaAsset?.format === 'DOCUMENT' && mediaAsset.providerUrl && <a href={mediaAsset.providerUrl} target="_blank" rel="noreferrer">查看已上传文档</a>}<Button onClick={clearMediaUpload}>替换素材</Button></Space> : <Input aria-label="Header 素材" type="file" accept={headerFormat === 'IMAGE' ? 'image/jpeg,image/png' : headerFormat === 'VIDEO' ? 'video/mp4' : 'application/pdf'} onChange={(event) => { const file = event.target.files?.[0]; if (file) void handleUpload(file); }} />}{uploading && <Alert style={{ marginTop: 8 }} type="info" message="正在上传素材" showIcon />}{uploadError && <Alert style={{ marginTop: 8 }} type="error" message={uploadError} showIcon />}</Form.Item>}
        <Form.Item label="Footer"><Input.TextArea aria-label="Footer" value={footer} onChange={(event) => setFooter(event.target.value)} rows={2} /></Form.Item>
        <Form.Item label="按钮"><Space direction="vertical" style={{ width: '100%' }}>{buttons.map((button, index) => <Space key={`${button.type}-${index}`} style={{ width: '100%' }} wrap><Select aria-label={`按钮 ${index + 1} 类型`} value={button.type} options={[{ value: 'QUICK_REPLY', label: '快速回复' }, { value: 'URL', label: '网址' }, { value: 'PHONE_NUMBER', label: '电话' }]} onChange={(type) => setButtons((current) => current.map((item, itemIndex) => itemIndex === index ? { type, text: item.text ?? '', url: null, phoneNumber: null } : item))} /><Input aria-label={`按钮 ${index + 1} 文案`} value={button.text ?? ''} onChange={(event) => setButtons((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, text: event.target.value } : item))} />{button.type === 'URL' && <Input aria-label={`按钮 ${index + 1} 网址`} value={button.url ?? ''} onChange={(event) => setButtons((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, url: event.target.value } : item))} />}{button.type === 'PHONE_NUMBER' && <Input aria-label={`按钮 ${index + 1} 电话`} value={button.phoneNumber ?? ''} onChange={(event) => setButtons((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, phoneNumber: event.target.value } : item))} />}<Button type="text" danger aria-label={`删除按钮 ${index + 1}`} icon={<DeleteOutlined />} onClick={() => setButtons((current) => current.filter((_, itemIndex) => itemIndex !== index))} /></Space>)}<Button onClick={() => setButtons((current) => [...current, { type: 'QUICK_REPLY', text: '', url: null, phoneNumber: null }])}>添加按钮</Button></Space></Form.Item>
        {sourceContext && sourceContext.unresolvedButtons.length > 0 && <section style={{ marginBottom: 16 }}><Typography.Title level={5}>未转换的来源按钮</Typography.Title><Space direction="vertical" style={{ width: '100%' }}>{sourceContext.unresolvedButtons.map(({ id, button }) => {
          const label = sourceButtonLabel(id, button.name);
          const resolution = resolutions[id] ?? { action: 'UNRESOLVED', text: button.name ?? '', url: button.url ?? '', phoneNumber: '' };
          return <Space direction="vertical" key={id} style={{ width: '100%' }}>
            <Select aria-label={`源按钮 ${label} 处理方式`} value={resolution.action} options={[{ value: 'UNRESOLVED', label: '待处理' }, { value: 'OMIT', label: '不添加' }, { value: 'QUICK_REPLY', label: '快速回复' }, { value: 'URL', label: '网址' }, { value: 'PHONE_NUMBER', label: '电话' }]} onChange={(action: SourceButtonResolutionAction) => setResolutions((current) => ({ ...current, [id]: { ...resolution, action } }))} />
            {resolution.action !== 'UNRESOLVED' && resolution.action !== 'OMIT' && <Input aria-label={`源按钮 ${label} 文案`} value={resolution.text} onChange={(event) => setResolutions((current) => ({ ...current, [id]: { ...resolution, text: event.target.value } }))} />}
            {resolution.action === 'URL' && <Input aria-label={`源按钮 ${label} 网址`} value={resolution.url} onChange={(event) => setResolutions((current) => ({ ...current, [id]: { ...resolution, url: event.target.value } }))} />}
            {resolution.action === 'PHONE_NUMBER' && <Input aria-label={`源按钮 ${label} 电话`} value={resolution.phoneNumber} onChange={(event) => setResolutions((current) => ({ ...current, [id]: { ...resolution, phoneNumber: event.target.value } }))} />}
          </Space>;
        })}</Space></section>}
      </Form>;

  const previewPage = sourceContext?.selectedPageIndex === null
    ? { name: null, text: null, buttons: [] }
    : sourceContext?.sourceTemplate.content.pages[sourceContext.selectedPageIndex ?? -1] ?? { name: null, text: null, buttons: [] };

  return (
    <Drawer title={template ? '编辑模板' : '新建模板'} open={open} onClose={handleClose} width={sourceContext ? 'min(1100px, 100vw)' : 'min(680px, 100vw)'} destroyOnClose footer={<Space><Button onClick={handleClose}>取消</Button><Button type="primary" disabled={blockers.length > 0} loading={submitting} onClick={submit}>{template ? '提交修改' : '提交创建'}</Button></Space>}>
      {blockers.length > 0 && <Alert type="error" showIcon message={blockers[0]} style={{ marginBottom: 16 }} />}
      {sourceContext ? <div className="template-editor-workbench"><div className="template-editor-workbench__form">{form}</div><aside className="template-editor-workbench__source"><Typography.Title level={5}>公共模板来源</Typography.Title><TemplateMessagePreview page={previewPage} variables={sourceContext.sourceTemplate.content.variables} mode="example" /></aside></div> : form}
    </Drawer>
  );
}
