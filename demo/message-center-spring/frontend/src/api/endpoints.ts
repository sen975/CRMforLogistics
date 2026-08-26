import client from './client';
import type {
  ContactResponse,
  ConversationPage,
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
  PublicTemplateListPage,
  PublicTemplateQuery,
  ChatAppBroadcast,
  ChatAppBroadcastDetail,
  ChatAppBroadcastPage,
  ChatAppBroadcastRecipientPage,
  CreateChatAppBroadcastCommand,
  WeComLoginAttempt,
  WeComLoginResponse,
  WeComBindingResponse,
  WeComJsSdkConfig,
  WeComViewerBootstrapResponse,
  WeComViewerSessionDetail,
  WeComViewerSessionResponse,
  WeComInstallationSummary,
  WeComProviderData,
} from './types';

export async function login(data: LoginRequest): Promise<LoginResponse> {
  const res = await client.post<LoginResponse>('/auth/login', data);
  return res.data;
}

export async function logout(): Promise<void> {
  await client.post('/auth/logout');
}

export async function createWeComAttempt(
  purpose: 'login' | 'bind',
): Promise<WeComLoginAttempt> {
  const path = purpose === 'login'
    ? '/auth/wecom/attempts'
    : '/account/wecom-binding/attempts';
  const res = await client.post<WeComLoginAttempt>(path);
  return res.data;
}

export async function exchangeWeComLogin(data: {
  code: string;
  state: string;
}): Promise<WeComLoginResponse> {
  const res = await client.post<WeComLoginResponse>('/auth/wecom/exchange', data);
  return res.data;
}

export async function fetchWeComBinding(): Promise<WeComBindingResponse> {
  const res = await client.get<WeComBindingResponse>('/account/wecom-binding');
  return res.data;
}

export async function exchangeWeComBinding(data: {
  code: string;
  state: string;
}): Promise<WeComBindingResponse> {
  const res = await client.post<WeComBindingResponse>('/account/wecom-binding/exchange', data);
  return res.data;
}

export async function unbindWeCom(): Promise<void> {
  await client.delete('/account/wecom-binding');
}

const weComViewerBase = '/v1/wecom/conversation-view';

function weComViewerHeaders(viewerAuthToken: string) {
  return { 'X-WeCom-Viewer-Token': viewerAuthToken };
}

export async function bootstrapWeComViewer(): Promise<WeComViewerBootstrapResponse> {
  const res = await client.post<WeComViewerBootstrapResponse>(`${weComViewerBase}/bootstrap`);
  return res.data;
}

export async function fetchWeComJsSdkConfig(
  url: string,
  viewerAuthToken: string,
): Promise<WeComJsSdkConfig> {
  const res = await client.get<WeComJsSdkConfig>('/v1/wecom/js-sdk-config', {
    params: { url },
    headers: weComViewerHeaders(viewerAuthToken),
  });
  return res.data;
}

export async function createWeComViewerSession(
  contactPointId: string,
  messageIds: string[],
  viewerAuthToken: string,
  options?: { signal?: AbortSignal },
): Promise<WeComViewerSessionResponse> {
  const res = await client.post<WeComViewerSessionResponse>(
    `${weComViewerBase}/sessions`,
    { contactPointId, messageIds },
    { headers: weComViewerHeaders(viewerAuthToken), signal: options?.signal },
  );
  return res.data;
}

export async function fetchWeComViewerSession(
  viewerSessionId: string,
  viewerAuthToken: string,
  options?: { signal?: AbortSignal },
): Promise<WeComViewerSessionDetail> {
  const res = await client.get<WeComViewerSessionDetail>(
    `${weComViewerBase}/sessions/${encodeURIComponent(viewerSessionId)}`,
    { headers: weComViewerHeaders(viewerAuthToken), signal: options?.signal },
  );
  return res.data;
}

export async function recordWeComViewerEvent(
  viewerSessionId: string,
  viewerAuthToken: string,
): Promise<void> {
  await client.post(
    `${weComViewerBase}/events`,
    { eventType: 'component_error', viewerSessionId },
    { headers: weComViewerHeaders(viewerAuthToken) },
  );
}

const weComP0Base = '/v1/wecom/installations';
const encoded = (value: string) => encodeURIComponent(value);
const weComInstallationBase = (authCorpId: string) => `${weComP0Base}/${encoded(authCorpId)}`;

export async function fetchWeComInstallations(): Promise<WeComInstallationSummary[]> {
  const res = await client.get<WeComInstallationSummary[]>('/v1/wecom/installations');
  return res.data;
}

export async function createWeComAppChat(
  authCorpId: string,
  data: { chatId?: string; name: string; owner: string; userList: string[] },
): Promise<WeComProviderData> {
  const res = await client.post<WeComProviderData>(`${weComInstallationBase(authCorpId)}/app-chats`, data);
  return res.data;
}

export async function fetchWeComAppChat(authCorpId: string, chatId: string): Promise<WeComProviderData> {
  const res = await client.get<WeComProviderData>(
    `${weComInstallationBase(authCorpId)}/app-chats/${encoded(chatId)}`,
  );
  return res.data;
}

export async function updateWeComAppChat(
  authCorpId: string,
  chatId: string,
  data: { name?: string; owner?: string; addUsers?: string[]; removeUsers?: string[] },
): Promise<WeComProviderData> {
  const res = await client.patch<WeComProviderData>(
    `${weComInstallationBase(authCorpId)}/app-chats/${encoded(chatId)}`, data,
  );
  return res.data;
}

export async function sendWeComAppChatMessage(
  authCorpId: string,
  chatId: string,
  data: { messageType: string; content: WeComProviderData; safe?: boolean },
): Promise<WeComProviderData> {
  const res = await client.post<WeComProviderData>(
    `${weComInstallationBase(authCorpId)}/app-chats/${encoded(chatId)}/messages`, data,
  );
  return res.data;
}

export async function listWeComExternalContacts(
  authCorpId: string,
  userId: string,
): Promise<WeComProviderData> {
  const res = await client.get<WeComProviderData>(`${weComInstallationBase(authCorpId)}/external-contacts`, {
    params: { userId },
  });
  return res.data;
}

export async function getWeComExternalContact(
  authCorpId: string,
  externalUserId: string,
  cursor?: string,
): Promise<WeComProviderData> {
  const res = await client.get<WeComProviderData>(
    `${weComInstallationBase(authCorpId)}/external-contacts/${encoded(externalUserId)}`,
    { params: cursor ? { cursor } : undefined },
  );
  return res.data;
}

export async function batchGetWeComExternalContacts(
  authCorpId: string,
  data: { userIds: string[]; cursor?: string; limit?: number },
): Promise<WeComProviderData> {
  const res = await client.post<WeComProviderData>(
    `${weComInstallationBase(authCorpId)}/external-contacts:batchGet`, data,
  );
  return res.data;
}

export async function updateWeComExternalContactRemark(
  authCorpId: string,
  externalUserId: string,
  userId: string,
  data: { remark?: string; description?: string; remarkCompany?: string; remarkMobiles?: string[] },
): Promise<WeComProviderData> {
  const res = await client.patch<WeComProviderData>(
    `${weComInstallationBase(authCorpId)}/external-contacts/${encoded(externalUserId)}/remark`,
    data, { params: { userId } },
  );
  return res.data;
}

export async function searchWeComCustomerGroups(
  authCorpId: string,
  data: { statusFilter?: number; ownerFilter?: string[]; cursor?: string; limit?: number },
): Promise<WeComProviderData> {
  const res = await client.post<WeComProviderData>(
    `${weComInstallationBase(authCorpId)}/customer-groups:search`, data,
  );
  return res.data;
}

export async function fetchWeComCustomerGroup(
  authCorpId: string,
  chatId: string,
  needName = false,
): Promise<WeComProviderData> {
  const res = await client.get<WeComProviderData>(
    `${weComInstallationBase(authCorpId)}/customer-groups/${encoded(chatId)}`,
    { params: { needName } },
  );
  return res.data;
}

export async function fetchWeComDirectoryMember(
  authCorpId: string,
  userId: string,
): Promise<WeComProviderData> {
  const res = await client.get<WeComProviderData>(
    `${weComInstallationBase(authCorpId)}/directory/members/${encoded(userId)}`,
  );
  return res.data;
}

export async function listWeComDirectoryMembers(
  authCorpId: string,
  departmentId: number,
  fetchChild = false,
): Promise<WeComProviderData> {
  const res = await client.get<WeComProviderData>(`${weComInstallationBase(authCorpId)}/directory/members`, {
    params: { departmentId, fetchChild },
  });
  return res.data;
}

export async function listWeComDepartments(
  authCorpId: string,
  departmentId?: number,
): Promise<WeComProviderData> {
  const res = await client.get<WeComProviderData>(`${weComInstallationBase(authCorpId)}/directory/departments`, {
    params: departmentId === undefined ? undefined : { departmentId },
  });
  return res.data;
}

export async function listWeComTags(authCorpId: string): Promise<WeComProviderData> {
  const res = await client.get<WeComProviderData>(`${weComInstallationBase(authCorpId)}/directory/tags`);
  return res.data;
}

export async function syncWeComDirectoryProfiles(authCorpId: string): Promise<{
  departments: number;
  discovered: number;
  ready: number;
  partial: number;
  degraded: number;
}> {
  const res = await client.post(`${weComInstallationBase(authCorpId)}/directory/profile-sync`);
  return res.data;
}

export async function fetchWeComTag(authCorpId: string, tagId: number): Promise<WeComProviderData> {
  const res = await client.get<WeComProviderData>(
    `${weComInstallationBase(authCorpId)}/directory/tags/${tagId}`,
  );
  return res.data;
}

export async function fetchContacts(params?: {
  search?: string;
  page?: number;
  size?: number;
  channelType?: string;
  channelAccountId?: string;
}): Promise<MyBatisPage<ContactResponse>> {
  const res = await client.get<MyBatisPage<ContactResponse>>('/contacts', { params });
  return res.data;
}

export async function listConversations(params?: {
  search?: string;
  cursor?: string;
  limit?: number;
}): Promise<ConversationPage> {
  const res = await client.get<ConversationPage>('/conversations', { params });
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
  contactId: string;
  recipientIdentityId: string;
  text?: string;
  templateCode?: string;
  templateName?: string;
  languageCode?: string;
  templateParamsJson?: string;
  clientRequestId?: string;
}): Promise<void> {
  if (data.mode === 'template') {
    await client.post('/chatapp/send/template', {
      contactId: data.contactId,
      recipientIdentityId: data.recipientIdentityId,
      templateCode: data.templateCode,
      templateName: data.templateName,
      languageCode: data.languageCode,
      templateParams: data.templateParamsJson,
      clientRequestId: data.clientRequestId,
    });
  } else {
    await client.post('/chatapp/send/text', {
      contactId: data.contactId,
      recipientIdentityId: data.recipientIdentityId,
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

const chatAppBroadcastBase = '/v1/chatapp/broadcasts';

export async function fetchChatAppBroadcasts(
  channelAccountId: string,
  page = 1,
  size = 20,
): Promise<ChatAppBroadcastPage> {
  const res = await client.get<ChatAppBroadcastPage>(chatAppBroadcastBase, {
    params: { channelAccountId, page, size },
  });
  return res.data;
}

export async function fetchChatAppBroadcastTemplates(
  channelAccountId: string,
): Promise<TemplateResponse[]> {
  const res = await client.get<TemplateResponse[]>(`${chatAppBroadcastBase}/sendable-templates`, {
    params: { channelAccountId },
  });
  return res.data;
}

export async function fetchChatAppBroadcastFailures(
  broadcastId: string,
  page = 1,
  size = 20,
): Promise<ChatAppBroadcastRecipientPage> {
  const res = await client.get<ChatAppBroadcastRecipientPage>(
    `${chatAppBroadcastBase}/${broadcastId}/failures`,
    { params: { page, size } },
  );
  return res.data;
}

export async function fetchChatAppBroadcastDetail(
  broadcastId: string,
): Promise<ChatAppBroadcastDetail> {
  const res = await client.get<ChatAppBroadcastDetail>(`${chatAppBroadcastBase}/${broadcastId}`);
  return res.data;
}

export async function requestChatAppBroadcastReconciliation(
  broadcastId: string,
): Promise<ChatAppBroadcast> {
  const res = await client.post<ChatAppBroadcast>(`${chatAppBroadcastBase}/${broadcastId}/reconcile`);
  return res.data;
}

export async function createChatAppBroadcast(
  command: CreateChatAppBroadcastCommand,
): Promise<ChatAppBroadcast> {
  const res = await client.post<ChatAppBroadcast>(chatAppBroadcastBase, command);
  return res.data;
}

export async function retryChatAppBroadcastFailures(
  broadcastId: string,
  command: { name: string; clientRequestId: string },
): Promise<ChatAppBroadcast> {
  const res = await client.post<ChatAppBroadcast>(
    `${chatAppBroadcastBase}/${broadcastId}/retry-failures`,
    command,
  );
  return res.data;
}

export async function fetchMediaUrl(id: string): Promise<string> {
  const res = await client.get(`/media/${id}`, { responseType: 'blob' });
  return URL.createObjectURL(res.data);
}

export async function fetchMediaBlob(id: string): Promise<Blob> {
  const res = await client.get(`/media/${id}`, { responseType: 'blob' });
  return res.data;
}

export async function downloadAttachment(id: string, fileName: string): Promise<void> {
  const res = await client.get(`/media/${id}/download`, { responseType: 'blob' });
  const url = URL.createObjectURL(res.data);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

export async function sendWeCom(data: { corpId: string; agentId: string; to: string; text: string }): Promise<void> {
  await client.post('/wecom/send', data);
}

export async function sendChatAppMedia(data: {
  contactId: string;
  recipientIdentityId: string;
  mediaType: string;
  caption?: string;
  file: File;
  clientRequestId?: string;
}): Promise<void> {
  const formData = new FormData();
  formData.append('contactId', data.contactId);
  formData.append('recipientIdentityId', data.recipientIdentityId);
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

export async function updateAdminTemplateRemark(
  accountId: string,
  templateCode: string,
  language: string,
  remark: string,
): Promise<TemplateAdmin> {
  const res = await client.put<TemplateAdmin>(
    `${whatsappManagementBase(accountId)}/templates/${templateCode}/remark`,
    { remark },
    { params: { language } },
  );
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

function boundedPublicTemplateQuery(query: PublicTemplateQuery): Record<string, unknown> {
  const page = Math.max(1, Math.floor(query.page ?? 1));
  const size = Math.min(200, Math.max(1, Math.floor(query.size ?? 20)));
  const split = (values?: string[]) => values
    ?.map((value) => value.trim().slice(0, 120))
    .filter(Boolean)
    .slice(0, 20);
  return Object.fromEntries(Object.entries({
    name: query.name?.trim().slice(0, 120) || undefined,
    language: query.language?.trim().slice(0, 32) || undefined,
    category: query.category?.trim().slice(0, 64) || undefined,
    industries: split(query.industries),
    usecases: split(query.usecases),
    page,
    size,
  }).filter(([, value]) => value !== undefined));
}

export async function fetchPublicTemplates(accountId: string, query: PublicTemplateQuery = {}): Promise<PublicTemplateListPage> {
  const res = await client.get<PublicTemplateListPage>(`${whatsappManagementBase(accountId)}/public-templates`, {
    params: boundedPublicTemplateQuery(query),
    paramsSerializer: { indexes: null },
  });
  return res.data;
}
