import client from './client';
import type {
  ContactResponse,
  LoginRequest,
  LoginResponse,
  MyBatisPage,
  TemplateResponse,
  ThreadResponse,
  MessageResponse,
  ChannelCapability,
  TemplateAdmin,
  TemplateCommand,
  TemplateListFilters,
  TemplateListPage,
  TemplateMediaAsset,
  TemplateMediaFormat,
  TemplateOperation,
  TemplateSyncResult,
  TemplateUpdateCommand,
} from './types';

export async function login(data: LoginRequest): Promise<LoginResponse> {
  const res = await client.post<LoginResponse>('/auth/login', data);
  return res.data;
}

export async function logout(): Promise<void> {
  await client.post('/auth/logout');
}

export async function fetchContacts(params?: {
  search?: string;
  page?: number;
  size?: number;
}): Promise<MyBatisPage<ContactResponse>> {
  const res = await client.get<MyBatisPage<ContactResponse>>('/contacts', { params });
  return res.data;
}

export async function fetchContact(id: string): Promise<ContactResponse> {
  const res = await client.get<ContactResponse>(`/contacts/${id}`);
  return res.data;
}

export async function markContactRead(id: string): Promise<void> {
  await client.post(`/contacts/${id}/mark-read`);
}

export async function updateContactRemark(id: string, remark: string): Promise<void> {
  await client.post(`/contacts/${id}/remark`, { remark });
}

export async function updateContactProfile(
  id: string,
  data: { displayName?: string; roleTitle?: string },
): Promise<void> {
  await client.post(`/contacts/${id}/profile`, data);
}

export async function mergeContacts(sourceContactId: string, targetContactId: string): Promise<void> {
  await client.post('/contact-groups/merge', { sourceContactId, targetContactId });
}

export async function splitContact(identityId: string, newContactName?: string): Promise<void> {
  await client.post('/contact-groups/split', { identityId, newContactName });
}

export async function fetchThread(
  contactId: string,
  params?: { cursor?: string; limit?: number },
): Promise<ThreadResponse> {
  const res = await client.get<ThreadResponse>('/threads', {
    params: { contactId, ...params },
  });
  return res.data;
}

export async function fetchMessage(id: string): Promise<MessageResponse> {
  const res = await client.get<MessageResponse>(`/messages/${id}`);
  return res.data;
}

export async function sendEmail(data: { to: string; subject: string; body: string; attachments?: File[] }): Promise<void> {
  if (data.attachments && data.attachments.length > 0) {
    const formData = new FormData();
    formData.append('to', data.to);
    formData.append('subject', data.subject);
    formData.append('body', data.body);
    for (const file of data.attachments) {
      formData.append('file', file);
    }
    await client.post('/send/email', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  } else {
    await client.post('/send/email', data);
  }
}

export async function sendChatApp(data: {
  mode: string;
  to: string;
  text?: string;
  templateCode?: string;
  templateName?: string;
  languageCode?: string;
  templateParamsJson?: string;
  clientRequestId?: string;
}): Promise<void> {
  if (data.mode === 'template') {
    await client.post('/chatapp/send/template', {
      to: data.to,
      templateCode: data.templateCode,
      templateName: data.templateName,
      languageCode: data.languageCode,
      templateParams: data.templateParamsJson,
      clientRequestId: data.clientRequestId,
    });
  } else {
    await client.post('/chatapp/send/text', {
      to: data.to,
      text: data.text,
      clientRequestId: data.clientRequestId,
    });
  }
}

export async function fetchTemplates(): Promise<TemplateResponse[]> {
  const res = await client.get<TemplateResponse[]>('/templates');
  return res.data;
}

export async function fetchChannelCapabilities(): Promise<ChannelCapability[]> {
  const res = await client.get<ChannelCapability[]>('/channel-capabilities');
  return res.data;
}

export async function fetchMediaUrl(id: string): Promise<string> {
  const res = await client.get(`/media/${id}`, { responseType: 'blob' });
  return URL.createObjectURL(res.data);
}

export async function sendWeCom(data: { corpId: string; agentId: string; to: string; text: string }): Promise<void> {
  await client.post('/wecom/send', data);
}

export async function sendChatAppMedia(data: {
  to: string;
  mediaType: string;
  caption?: string;
  file: File;
  clientRequestId?: string;
}): Promise<void> {
  const formData = new FormData();
  formData.append('to', data.to);
  formData.append('mediaType', data.mediaType);
  if (data.caption) formData.append('caption', data.caption);
  formData.append('clientRequestId', data.clientRequestId ?? crypto.randomUUID());
  formData.append('file', data.file);
  await client.post('/send/chatapp-media', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export async function fetchChannelAccounts(): Promise<import('./types').ChannelAccount[]> {
  const res = await client.get<import('./types').ChannelAccount[]>('/channel-accounts');
  return res.data;
}

export async function updateChannelAccount(id: string, data: { name?: string; accountIdentifier?: string }): Promise<import('./types').ChannelAccount> {
  const res = await client.put<import('./types').ChannelAccount>(`/channel-accounts/${id}`, data);
  return res.data;
}

export async function triggerChannelSync(id: string): Promise<import('./types').SyncResult> {
  const res = await client.post<import('./types').SyncResult>(`/channel-accounts/${id}/sync`);
  return res.data;
}

export async function fetchChannelCredentials(id: string): Promise<import('./types').ChannelCredentials> {
  const res = await client.get<import('./types').ChannelCredentials>(`/channel-accounts/${id}/credentials`);
  return res.data;
}

export async function updateChannelCredentials(id: string, data: import('./types').ChannelCredentials): Promise<{ updated: number }> {
  const res = await client.put<{ updated: number }>(`/channel-accounts/${id}/credentials`, data);
  return res.data;
}

export async function fetchTimeline(
  contactId: string,
  params?: { cursor?: string; limit?: number },
): Promise<import('./types').TimelineResponse> {
  const res = await client.get<import('./types').TimelineResponse>(
    `/v1/contacts/${contactId}/timeline`,
    { params },
  );
  return res.data;
}

export async function createCallRecord(
  contactId: string,
  formData: FormData,
): Promise<{ callRecordId: string; state: string }> {
  const res = await client.post<{ callRecordId: string; state: string }>(
    `/v1/contacts/${contactId}/call-records`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
  return res.data;
}

export async function fetchCallRecord(id: string): Promise<import('./types').CallRecordResponse> {
  const res = await client.get<import('./types').CallRecordResponse>(`/v1/call-records/${id}`);
  return res.data;
}

export async function createAudioSession(id: string): Promise<void> {
  await client.post(`/v1/call-records/${id}/audio-sessions`);
}

export async function retryCallRecord(
  id: string,
  clientRequestId: string,
): Promise<import('./types').CallRecordResponse> {
  const res = await client.post<import('./types').CallRecordResponse>(
    `/v1/call-records/${id}/retry`,
    { clientRequestId },
  );
  return res.data;
}

export async function reviseTranscript(
  id: string,
  data: { text: string; expectedVersion: number },
): Promise<import('./types').CallRecordResponse> {
  const res = await client.patch<import('./types').CallRecordResponse>(
    `/v1/call-records/${id}/transcript`,
    data,
  );
  return res.data;
}

export async function reviseNote(
  id: string,
  data: { note: string; expectedVersion: number },
): Promise<import('./types').CallRecordResponse> {
  const res = await client.patch<import('./types').CallRecordResponse>(
    `/v1/call-records/${id}/note`,
    data,
  );
  return res.data;
}

export async function bindPhoneContact(data: {
  contactId?: string;
  contactName?: string;
  phoneNumber: string;
}): Promise<{ contactId: string; phonePointId: string; displayName: string }> {
  const res = await client.post<{ contactId: string; phonePointId: string; displayName: string }>(
    '/v1/phone-contacts',
    data,
  );
  return res.data;
}

export async function fetchPhoneRepository(params?: {
  cursor?: string;
  limit?: number;
  query?: string;
}): Promise<import('./types').PhoneRecordPage> {
  const res = await client.get<import('./types').PhoneRecordPage>('/v1/phone-repository', { params });
  return res.data;
}

function whatsappManagementBase(accountId: string): string {
  return `/v1/channel-accounts/${accountId}/whatsapp`;
}

export async function fetchAdminTemplates(
  accountId: string,
  params?: TemplateListFilters,
): Promise<TemplateListPage> {
  const res = await client.get<TemplateListPage>(`${whatsappManagementBase(accountId)}/templates`, { params });
  return res.data;
}

export async function fetchAdminTemplate(
  accountId: string,
  templateCode: string,
  language: string,
): Promise<TemplateAdmin> {
  const res = await client.get<TemplateAdmin>(`${whatsappManagementBase(accountId)}/templates/${templateCode}`, {
    params: { language },
  });
  return res.data;
}

export async function createAdminTemplate(
  accountId: string,
  command: TemplateCommand,
): Promise<TemplateOperation> {
  const res = await client.post<TemplateOperation>(`${whatsappManagementBase(accountId)}/templates`, command);
  return res.data;
}

export async function updateAdminTemplate(
  accountId: string,
  templateCode: string,
  language: string,
  command: TemplateUpdateCommand,
): Promise<TemplateOperation> {
  const res = await client.put<TemplateOperation>(`${whatsappManagementBase(accountId)}/templates/${templateCode}`, command, {
    params: { language },
  });
  return res.data;
}

export async function setAdminTemplateSendPermission(
  accountId: string,
  templateCode: string,
  language: string,
  allowSend: boolean,
  clientRequestId: string,
): Promise<TemplateOperation> {
  const res = await client.put<TemplateOperation>(
    `${whatsappManagementBase(accountId)}/templates/${templateCode}/send-permission`,
    { allowSend, clientRequestId },
    { params: { language } },
  );
  return res.data;
}

export async function deleteAdminTemplate(
  accountId: string,
  templateCode: string,
  language: string,
  clientRequestId: string,
): Promise<TemplateOperation> {
  const res = await client.delete<TemplateOperation>(`${whatsappManagementBase(accountId)}/templates/${templateCode}`, {
    params: { language, clientRequestId },
  });
  return res.data;
}

export async function syncAdminTemplates(accountId: string): Promise<TemplateSyncResult> {
  const res = await client.post<TemplateSyncResult>(`${whatsappManagementBase(accountId)}/templates/sync`);
  return res.data;
}

export async function uploadTemplateMedia(
  accountId: string,
  format: TemplateMediaFormat,
  file: File,
  clientRequestId: string,
  signal?: AbortSignal,
): Promise<TemplateMediaAsset> {
  const formData = new FormData();
  formData.append('format', format);
  formData.append('file', file);
  formData.append('clientRequestId', clientRequestId);
  const res = signal
    ? await client.post<TemplateMediaAsset>(`${whatsappManagementBase(accountId)}/template-media`, formData, { signal })
    : await client.post<TemplateMediaAsset>(`${whatsappManagementBase(accountId)}/template-media`, formData);
  return res.data;
}

export async function fetchTemplateMediaUpload(
  accountId: string,
  clientRequestId: string,
  signal?: AbortSignal,
): Promise<TemplateMediaAsset> {
  const encoded = encodeURIComponent(clientRequestId);
  const res = signal
    ? await client.get<TemplateMediaAsset>(`${whatsappManagementBase(accountId)}/template-media/uploads/${encoded}`, { signal })
    : await client.get<TemplateMediaAsset>(`${whatsappManagementBase(accountId)}/template-media/uploads/${encoded}`);
  return res.data;
}

export async function fetchTemplateOperations(
  accountId: string,
  templateCode: string,
  language: string,
): Promise<TemplateOperation[]> {
  const res = await client.get<TemplateOperation[]>(
    `${whatsappManagementBase(accountId)}/templates/${templateCode}/operations`,
    { params: { language } },
  );
  return res.data;
}
