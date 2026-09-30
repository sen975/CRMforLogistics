import client from './client';
import type {
  ContactResponse,
  ContactMemoryResponse,
  ConversationPage,
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  AccountProfile,
  ChangePasswordRequest,
  AdminUser,
  AdminUserPage,
  AccountRole,
  MyBatisPage,
  TemplateResponse,
  ThreadResponse,
  MessageResponse,
  ChannelCapability,
  TemplateCommand,
  TemplateMediaAsset,
  TemplateMediaFormat,
  TemplateOperation,
  TemplateSyncResult,
  TemplateUpdateCommand,
  SharedTemplate,
  SharedTemplateListFilters,
  SharedTemplateListPage,
  TemplateChangeRequestPage,
  TemplateChangeRequestView,
  TemplateChangeOutcome,
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
  WeComAvatarAuthorizationAttempt,
  WeComAvatarAuthorizationProjection,
  WeComJsSdkConfig,
  WeComViewerBootstrapResponse,
  WeComViewerSessionDetail,
  WeComViewerSessionResponse,
  WeComInstallationSummary,
  WeComContactEvent,
  WeComExternalContactLink,
  WeComProviderData,
  WeComThreadResponse,
  WeComGroupThreadResponse,
  WeComGroupTopicsResponse,
  WeComGroupNameRefreshResponse,
  ChannelAddressBookChannel,
  ChannelAddressBookItem,
  ChannelAddressBookPageResponse,
  SearchMode,
  WhatsAppAuthorizationAttempt,
  WhatsAppAuthorizationResult,
  WhatsAppPhoneOperationStatus,
  WhatsAppPhoneOperationProjection,
  WhatsAppAccountProjection,
  WhatsAppHistorySyncProjection,
  WhatsAppCapabilityStatus,
  AdminWhatsAppAccountProjection,
  AdminWhatsAppAssignmentAuditProjection,
  AdminWhatsAppAssignmentRequest,
  AdminWhatsAppSyncResult,
  AdminWhatsAppVersionedReasonRequest,
  AdminCamsScope,
  AdminCamsRequest,
  AdminWhatsAppOverview,
  AdminScopedWhatsAppAccount,
  AdminWhatsAppCallbackConfig,
  AdminWhatsAppPhoneCallbackRequest,
  AdminWhatsAppAccountCallbackRequest,
  AssistantConversationMessage,
  AssistantMessageRequest,
  AssistantTurnResult,
} from './types';
import {
  streamAssistantMessage,
  type AssistantStreamHandlers,
} from './assistantStream';

export async function login(data: LoginRequest): Promise<LoginResponse> {
  const res = await client.post<LoginResponse>('/auth/login', data);
  return res.data;
}

export async function register(data: RegisterRequest): Promise<LoginResponse> {
  const res = await client.post<LoginResponse>('/auth/register', data);
  return res.data;
}

export async function fetchAccountProfile(): Promise<AccountProfile> {
  const res = await client.get<AccountProfile>('/account/profile');
  return res.data;
}

export async function updateAccountProfile(displayName: string): Promise<AccountProfile> {
  const res = await client.patch<AccountProfile>('/account/profile', { displayName });
  return res.data;
}

export async function uploadAccountAvatar(file: File): Promise<AccountProfile> {
  const form = new FormData();
  form.append('file', file);
  const res = await client.post<AccountProfile>('/account/avatar', form);
  return res.data;
}

export async function deleteAccountAvatar(): Promise<AccountProfile> {
  const res = await client.delete<AccountProfile>('/account/avatar');
  return res.data;
}

export async function fetchAccountAvatarBlob(): Promise<Blob> {
  const res = await client.get<Blob>('/account/avatar/content', { responseType: 'blob' });
  return res.data;
}

export async function changeAccountPassword(data: ChangePasswordRequest): Promise<LoginResponse> {
  const res = await client.put<LoginResponse>('/account/password', data);
  return res.data;
}

export async function fetchAdminUsers(page = 0, size = 20): Promise<AdminUserPage> {
  const res = await client.get<AdminUserPage>('/admin/users', { params: { page, size } });
  return res.data;
}

export async function fetchAccountRoles(): Promise<AccountRole[]> {
  const res = await client.get<AccountRole[]>('/admin/roles');
  return res.data;
}

export async function replaceAdminUserRoles(userId: string, roles: string[]): Promise<AdminUser> {
  const res = await client.put<AdminUser>(`/admin/users/${encodeURIComponent(userId)}/roles`, { roles });
  return res.data;
}

export async function resetAdminUserPassword(
  userId: string,
  newPassword: string,
  confirmPassword: string,
): Promise<void> {
  await client.put(`/admin/users/${encodeURIComponent(userId)}/password`, { newPassword, confirmPassword });
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

export async function createWeComAvatarAuthorization(): Promise<WeComAvatarAuthorizationAttempt> {
  const res = await client.post<WeComAvatarAuthorizationAttempt>(
    '/account/wecom-avatar/authorizations',
  );
  return res.data;
}

export async function fetchWeComAvatarAuthorization(
  authorizationId: string,
): Promise<WeComAvatarAuthorizationProjection> {
  const res = await client.get<WeComAvatarAuthorizationProjection>(
    `/account/wecom-avatar/authorizations/${encodeURIComponent(authorizationId)}`,
  );
  return res.data;
}

export async function toggleConversationPinned(targetType: 'CONTACT' | 'WECOM_GROUP', targetId: string) {
  const res = await client.post<{ targetType: string; targetId: string; pinned: boolean; hidden: boolean }>(
    '/conversations/preferences/pin', { targetType, targetId },
  );
  return res.data;
}

export async function hideConversation(targetType: 'CONTACT' | 'WECOM_GROUP', targetId: string): Promise<void> {
  await client.post('/conversations/preferences/delete', { targetType, targetId });
}

/**
 * Clears a stored hidden preference for a conversation the account opens on purpose.
 * Idempotent: it is safe to call for conversations that were never hidden.
 */
export async function restoreConversation(targetType: 'CONTACT' | 'WECOM_GROUP', targetId: string) {
  const res = await client.post<{ targetType: string; targetId: string; pinned: boolean; hidden: boolean }>(
    '/conversations/preferences/restore', { targetType, targetId },
  );
  return res.data;
}

export async function reorderConversations(request: import('./types').ConversationOrderRequest): Promise<void> {
  await client.post('/conversations/preferences/order', request);
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

export async function createWeComViewerTargetSession(
  target: { targetType: 'CONTACT' | 'WECOM_GROUP'; targetId: string; chatId?: string | null },
  messageIds: string[],
  viewerAuthToken: string,
  options?: { signal?: AbortSignal },
): Promise<WeComViewerSessionResponse> {
  const { targetType, targetId } = target;
  const res = await client.post<WeComViewerSessionResponse>(
    `${weComViewerBase}/sessions`,
    { targetType, targetId, messageIds },
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
  event: {
    eventKey: string;
    stage: string;
    generation: number;
    viewerSessionId?: string;
    errorCategory: string;
  },
  viewerAuthToken: string,
): Promise<void> {
  await client.post(
    `${weComViewerBase}/events`,
    { eventType: 'component_error', ...event },
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

/**
 * 客户动态（客户关系变化流水）。一次请求就是一次数据库查询 —— 服务端不会为了补齐昵称去回查企微，
 * 所以这里的行只有密文 ID，没有 displayName。
 */
export async function listWeComContactEvents(
  authCorpId: string,
  params: { since?: string; changeType?: string; limit?: number } = {},
): Promise<WeComContactEvent[]> {
  const res = await client.get<WeComContactEvent[]>(
    `${weComInstallationBase(authCorpId)}/contact-events`,
    { params },
  );
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
): Promise<WeComProviderData> {
  const res = await client.get<WeComProviderData>(`${weComInstallationBase(authCorpId)}/external-contacts`);
  return res.data;
}

/**
 * Asks the server which CRM contact each 客户联系 row may open. The client never derives
 * accessibility itself; contacts without a CRM contact come back with a null contactId.
 */
export async function listWeComExternalContactLinks(
  authCorpId: string,
  externalUserIds: string[],
): Promise<WeComExternalContactLink[]> {
  if (externalUserIds.length === 0) return [];
  const res = await client.get<WeComExternalContactLink[]>(
    `${weComInstallationBase(authCorpId)}/external-contacts/contact-links`,
    { params: { externalUserIds } },
  );
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
  searchMode?: SearchMode;
  page?: number;
  size?: number;
  channelType?: string;
  channelAccountId?: string;
}): Promise<MyBatisPage<ContactResponse>> {
  const res = await client.get<MyBatisPage<ContactResponse>>('/contacts', { params });
  return res.data;
}

export async function fetchChannelAddressBook(
  channel: ChannelAddressBookChannel,
  params?: { query?: string; searchMode?: SearchMode; page?: number; size?: number },
): Promise<ChannelAddressBookPageResponse> {
  const res = await client.get<ChannelAddressBookPageResponse>(
    `/channel-address-books/${encodeURIComponent(channel)}`,
    { params },
  );
  return res.data;
}

export async function createManualChannelContact(
  channel: ChannelAddressBookChannel,
  payload: { displayName: string; address: string },
): Promise<ChannelAddressBookItem> {
  const res = await client.post<ChannelAddressBookItem>(
    `/channel-address-books/${encodeURIComponent(channel)}`,
    { channelType: channel, ...payload },
  );
  return res.data;
}

export async function deleteManualChannelContact(
  channel: ChannelAddressBookChannel,
  contactId: string,
): Promise<void> {
  await client.delete(`/channel-address-books/${encodeURIComponent(channel)}/${encodeURIComponent(contactId)}`);
}

export async function listConversations(params?: {
  search?: string;
  searchMode?: SearchMode;
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

export async function fetchContactMemory(
  id: string,
  params?: { limit?: number; cursor?: string | null },
): Promise<ContactMemoryResponse> {
  const res = await client.get<ContactMemoryResponse>(`/contacts/${id}/memory`, { params });
  return res.data;
}

export async function markContactRead(id: string): Promise<void> {
  await client.post(`/contacts/${id}/mark-read`);
}

export async function updateContactRemark(id: string, remark: string): Promise<void> {
  await client.post(`/contacts/${id}/remark`, { remark });
}

export async function updateContactTags(
  id: string,
  tags: Array<{ name: string; color?: string | null }>,
): Promise<void> {
  await client.put(`/contacts/${id}/tags`, { tags });
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

export async function fetchWeComContactThread(
  contactId: string,
  params?: { cursor?: string; limit?: number },
): Promise<WeComThreadResponse> {
  const res = await client.get<WeComThreadResponse>(`/threads/wecom/contact/${encodeURIComponent(contactId)}`, { params });
  return res.data;
}

export async function fetchWeComGroupThread(
  sourceConversationId: string,
  params?: { cursor?: string; limit?: number },
): Promise<WeComGroupThreadResponse> {
  const res = await client.get<WeComGroupThreadResponse>(`/threads/wecom/group/${encodeURIComponent(sourceConversationId)}`, { params });
  return res.data;
}

export async function fetchWeComGroupTopics(sourceConversationId: string): Promise<WeComGroupTopicsResponse> {
  const res = await client.get<WeComGroupTopicsResponse>(`/v1/wecom/groups/${encodeURIComponent(sourceConversationId)}/topics`);
  return res.data;
}

export async function retryWeComGroupTopicGeneration(sourceConversationId: string): Promise<import('./types').TopicGenerationProjection> {
  const res = await client.post<import('./types').TopicGenerationProjection>(`/v1/wecom/groups/${encodeURIComponent(sourceConversationId)}/topics/retry`);
  return res.data;
}

export async function refreshWeComGroupName(sourceConversationId: string): Promise<WeComGroupNameRefreshResponse> {
  const res = await client.post<WeComGroupNameRefreshResponse>(
    `/v1/wecom/groups/${encodeURIComponent(sourceConversationId)}/name-refresh`,
  );
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
  return fetchChatAppSendableTemplates(channelAccountId);
}

export async function fetchChatAppSendableTemplates(
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

export type TodoApiItem = { id: string; date: string; title: string; time?: string | null; note?: string | null; completed: boolean; createdAt: string };
export async function fetchTodos(): Promise<TodoApiItem[]> { return (await client.get<TodoApiItem[]>('/todos')).data; }
export async function createTodoApi(data: { date: string; title: string; time?: string; note?: string }): Promise<TodoApiItem> { return (await client.post<TodoApiItem>('/todos', data)).data; }
export async function updateTodoApi(id: string, completed: boolean): Promise<void> { await client.patch(`/todos/${encodeURIComponent(id)}`, { completed }); }
export async function deleteTodoApi(id: string): Promise<void> { await client.delete(`/todos/${encodeURIComponent(id)}`); }

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

export async function createChannelAccount(
  data: import('./types').CreateChannelAccountRequest,
): Promise<import('./types').ChannelAccount> {
  const res = await client.post<import('./types').ChannelAccount>('/channel-accounts', data);
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

export async function unbindChannelAccount(id: string): Promise<void> {
  await client.post(`/channel-accounts/${id}/unbind`);
}

export async function createWhatsAppAuthorizationAttempt(onboardingMode: 'BUSINESS_APP_COEXISTENCE' | 'API_ONLY' | 'ADMIN_API_WABA'): Promise<WhatsAppAuthorizationAttempt> {
  const providerMode = onboardingMode === 'BUSINESS_APP_COEXISTENCE' ? 'EMPLOYEE_BUSINESS_APP' : onboardingMode === 'API_ONLY' ? 'ADMIN_API_WABA' : onboardingMode;
  const res = await client.post<WhatsAppAuthorizationAttempt>('/whatsapp/authorization/attempts', { onboardingMode: providerMode });
  return res.data;
}

export async function fetchWhatsAppCapability(): Promise<WhatsAppCapabilityStatus> {
  const res = await client.get<WhatsAppCapabilityStatus>('/whatsapp/capability');
  return res.data;
}

export async function completeWhatsAppAuthorization(data: {
  attemptId: string;
  state: string;
  event: 'FINISH';
  wabaId: string;
  phoneNumberId: string;
  code: string;
}): Promise<WhatsAppAuthorizationResult> {
  const res = await client.post<WhatsAppAuthorizationResult>('/whatsapp/authorization/complete', data);
  return res.data;
}

export async function fetchWhatsAppPhoneOperations(): Promise<WhatsAppPhoneOperationProjection[]> {
  const res = await client.get<WhatsAppPhoneOperationProjection[]>('/whatsapp/api-phone-operations');
  return res.data;
}

export async function addWhatsAppPhoneNumber(data: {
  countryCode: string;
  phoneNumber: string;
  verifiedName: string;
  accountName: string;
  accountRemark?: string;
}): Promise<WhatsAppPhoneOperationStatus> {
  const res = await client.post<WhatsAppPhoneOperationStatus>('/whatsapp/api-phone-operations', data);
  return res.data;
}

export async function sendWhatsAppVerificationCode(operationId: string, data: { locale: string; method: string; confirmed: true }): Promise<WhatsAppPhoneOperationStatus> {
  const res = await client.post<WhatsAppPhoneOperationStatus>(`/whatsapp/api-phone-operations/${encodeURIComponent(operationId)}/verification-code`, data);
  return res.data;
}

export async function verifyWhatsAppPhoneNumber(operationId: string, verificationCode: string): Promise<WhatsAppPhoneOperationStatus> {
  const res = await client.post<WhatsAppPhoneOperationStatus>(`/whatsapp/api-phone-operations/${encodeURIComponent(operationId)}/verify`, { verificationCode });
  return res.data;
}

export async function fetchWhatsAppAccounts(): Promise<WhatsAppAccountProjection[]> {
  const res = await client.get<WhatsAppAccountProjection[]>('/whatsapp/accounts/me');
  return res.data;
}

export async function unlinkWhatsAppAccount(accountId: string, reason: string): Promise<void> {
  await client.delete(`/whatsapp/accounts/${encodeURIComponent(accountId)}`, { data: { reason } });
}

export async function requestWhatsAppHistorySync(accountId: string): Promise<WhatsAppHistorySyncProjection> {
  const res = await client.post<WhatsAppHistorySyncProjection>(`/whatsapp/accounts/${encodeURIComponent(accountId)}/history-sync`);
  return res.data;
}

export async function fetchAdminWhatsAppAccounts(): Promise<AdminWhatsAppAccountProjection[]> {
  const res = await client.get<AdminWhatsAppAccountProjection[]>('/admin/whatsapp/accounts');
  return res.data;
}

export async function fetchAdminCams(): Promise<AdminCamsScope[]> {
  return (await client.get<AdminCamsScope[]>('/admin/whatsapp/cams')).data;
}

export async function fetchAdminWhatsAppOverview(): Promise<AdminWhatsAppOverview> {
  return (await client.get<AdminWhatsAppOverview>('/admin/whatsapp/overview')).data;
}

export async function createAdminCams(data: AdminCamsRequest): Promise<AdminCamsScope> {
  return (await client.post<AdminCamsScope>('/admin/whatsapp/cams', data)).data;
}

export async function updateAdminCams(scopeId: string, data: AdminCamsRequest): Promise<AdminCamsScope> {
  return (await client.put<AdminCamsScope>(`/admin/whatsapp/cams/${encodeURIComponent(scopeId)}`, data)).data;
}

export async function blockAdminCams(scopeId: string, expectedVersion: number): Promise<AdminCamsScope> {
  return (await client.delete<AdminCamsScope>(`/admin/whatsapp/cams/${encodeURIComponent(scopeId)}`, { data: { expectedVersion } })).data;
}

export async function testAdminCams(scopeId: string): Promise<{ success: boolean; phoneCount: number; message: string }> {
  return (await client.post(`/admin/whatsapp/cams/${encodeURIComponent(scopeId)}/test`)).data;
}

export async function syncAdminCams(scopeId: string): Promise<AdminWhatsAppSyncResult> {
  return (await client.post<AdminWhatsAppSyncResult>(`/admin/whatsapp/cams/${encodeURIComponent(scopeId)}/sync`)).data;
}

export async function fetchAdminScopedWhatsAppAccounts(scopeId: string): Promise<AdminScopedWhatsAppAccount[]> {
  return (await client.get<AdminScopedWhatsAppAccount[]>(`/admin/whatsapp/cams/${encodeURIComponent(scopeId)}/accounts`)).data;
}

export async function fetchAdminWhatsAppCallbacks(scopeId: string): Promise<AdminWhatsAppCallbackConfig> {
  return (await client.get<AdminWhatsAppCallbackConfig>(
    `/admin/whatsapp/cams/${encodeURIComponent(scopeId)}/callbacks`,
  )).data;
}

export async function updateAdminWhatsAppPhoneCallback(
  scopeId: string,
  accountId: string,
  request: AdminWhatsAppPhoneCallbackRequest,
): Promise<AdminWhatsAppCallbackConfig> {
  return (await client.put<AdminWhatsAppCallbackConfig>(
    `/admin/whatsapp/cams/${encodeURIComponent(scopeId)}/callbacks/phones/${encodeURIComponent(accountId)}`,
    request,
  )).data;
}

export async function updateAdminWhatsAppAccountCallback(
  scopeId: string,
  request: AdminWhatsAppAccountCallbackRequest,
): Promise<AdminWhatsAppCallbackConfig> {
  return (await client.put<AdminWhatsAppCallbackConfig>(
    `/admin/whatsapp/cams/${encodeURIComponent(scopeId)}/callbacks/account`,
    request,
  )).data;
}

export async function assignAdminScopedWhatsAppAccount(scopeId: string, accountId: string, request: AdminWhatsAppAssignmentRequest): Promise<AdminWhatsAppAccountProjection> {
  return (await client.post<AdminWhatsAppAccountProjection>(`/admin/whatsapp/cams/${encodeURIComponent(scopeId)}/accounts/${encodeURIComponent(accountId)}/assign`, request)).data;
}

export async function reclaimAdminScopedWhatsAppAccount(scopeId: string, accountId: string, request: AdminWhatsAppVersionedReasonRequest): Promise<AdminWhatsAppAccountProjection> {
  return (await client.post<AdminWhatsAppAccountProjection>(`/admin/whatsapp/cams/${encodeURIComponent(scopeId)}/accounts/${encodeURIComponent(accountId)}/reclaim`, request)).data;
}

export async function transferAdminScopedWhatsAppAccount(scopeId: string, accountId: string, request: AdminWhatsAppAssignmentRequest): Promise<AdminWhatsAppAccountProjection> {
  return (await client.post<AdminWhatsAppAccountProjection>(`/admin/whatsapp/cams/${encodeURIComponent(scopeId)}/accounts/${encodeURIComponent(accountId)}/transfer`, request)).data;
}

export async function fetchAdminScopedAssignmentHistory(scopeId: string, accountId: string): Promise<AdminWhatsAppAssignmentAuditProjection[]> {
  return (await client.get<AdminWhatsAppAssignmentAuditProjection[]>(`/admin/whatsapp/cams/${encodeURIComponent(scopeId)}/accounts/${encodeURIComponent(accountId)}/assignment-history`)).data;
}

export async function syncAdminWhatsAppAccounts(): Promise<AdminWhatsAppSyncResult> {
  const res = await client.post<AdminWhatsAppSyncResult>('/admin/whatsapp/accounts/sync');
  return res.data;
}

export type WhatsAppCamsConfig = { configured: boolean; custSpaceId: string; accessKeyIdMasked: string; region: string; endpoint: string; source: string };
export async function fetchWhatsAppCamsConfig(): Promise<WhatsAppCamsConfig> { return (await client.get<WhatsAppCamsConfig>('/admin/whatsapp/cams')).data; }
export async function saveWhatsAppCamsConfig(data: { custSpaceId: string; accessKeyId: string; accessKeySecret: string; region: string; endpoint: string }): Promise<WhatsAppCamsConfig> { return (await client.put<WhatsAppCamsConfig>('/admin/whatsapp/cams', data)).data; }
export async function testWhatsAppCamsConfig(): Promise<{ success: boolean; phoneCount: number; message: string }> { return (await client.post('/admin/whatsapp/cams/test')).data; }

export async function assignAdminWhatsAppAccount(
  accountId: string,
  request: AdminWhatsAppAssignmentRequest,
): Promise<AdminWhatsAppAccountProjection> {
  const res = await client.post<AdminWhatsAppAccountProjection>(
    `/admin/whatsapp/accounts/${encodeURIComponent(accountId)}/assign`,
    request,
  );
  return res.data;
}

export async function reclaimAdminWhatsAppAccount(
  accountId: string,
  request: AdminWhatsAppVersionedReasonRequest,
): Promise<AdminWhatsAppAccountProjection> {
  const res = await client.post<AdminWhatsAppAccountProjection>(
    `/admin/whatsapp/accounts/${encodeURIComponent(accountId)}/reclaim`,
    request,
  );
  return res.data;
}

export async function transferAdminWhatsAppAccount(
  accountId: string,
  request: AdminWhatsAppAssignmentRequest,
): Promise<AdminWhatsAppAccountProjection> {
  const res = await client.post<AdminWhatsAppAccountProjection>(
    `/admin/whatsapp/accounts/${encodeURIComponent(accountId)}/transfer`,
    request,
  );
  return res.data;
}

export async function fetchAdminWhatsAppAssignmentHistory(
  accountId: string,
): Promise<AdminWhatsAppAssignmentAuditProjection[]> {
  const res = await client.get<AdminWhatsAppAssignmentAuditProjection[]>(
    `/admin/whatsapp/accounts/${encodeURIComponent(accountId)}/assignment-history`,
  );
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

export async function fetchContactTopics(contactId: string): Promise<import('./types').ContactTopicsResponse> {
  const res = await client.get<import('./types').ContactTopicsResponse>(`/v1/contacts/${contactId}/topics`);
  return res.data;
}

export async function fetchManualReviewSources(contactId: string, params?: { contactIdentityId?: string; from?: string; to?: string }): Promise<import('./types').ManualReviewSourceListResponse> {
  const res = await client.get<import('./types').ManualReviewSourceListResponse>(`/v1/contacts/${contactId}/topic-review/sources`, { params });
  return res.data;
}
export async function fetchManualReviewPending(contactId: string): Promise<import('./types').TopicProjection[]> {
  const res = await client.get<import('./types').TopicProjection[]>(`/v1/contacts/${contactId}/topic-review/pending`);
  return res.data;
}
export async function previewManualReview(contactId: string, data: { sourceIds: string[]; sourceFingerprint?: string; contactIdentityId: string; from?: string; to?: string }): Promise<import('./types').ManualReviewPreviewResponse> {
  const res = await client.post<import('./types').ManualReviewPreviewResponse>(`/v1/contacts/${contactId}/topic-review/preview`, data);
  return res.data;
}
export async function applyManualReview(contactId: string, previewId: string, sourceFingerprint: string, assignments: import('./types').ManualReviewAssignment[]): Promise<import('./types').ManualReviewApplyResponse> {
  const res = await client.post<import('./types').ManualReviewApplyResponse>(`/v1/contacts/${contactId}/topic-review/${previewId}/apply`, { sourceFingerprint, assignments }, { headers: { 'Idempotency-Key': crypto.randomUUID() } });
  return res.data;
}
export async function keepPendingTopic(topicId: string): Promise<import('./types').TopicProjection> {
  const res = await client.post<import('./types').TopicProjection>(`/v1/topics/${topicId}/keep`);
  return res.data;
}

export async function previewTopicFusion(contactId: string, data: import('./types').TopicFusionPreviewRequest): Promise<import('./types').TopicFusionPreviewResponse> {
  const { topicIds, expectedVersions } = data;
  const res = await client.post<import('./types').TopicFusionPreviewResponse>(
    `/v1/contacts/${contactId}/topic-fusion/preview`,
    { topicIds, expectedVersions },
  );
  return res.data;
}

export async function applyTopicFusion(contactId: string, previewId: string): Promise<import('./types').TopicProjection> {
  const res = await client.post<import('./types').TopicProjection>(`/v1/contacts/${contactId}/topic-fusion/${previewId}/apply`, {}, { headers: { 'Idempotency-Key': crypto.randomUUID() } });
  return res.data;
}

export async function updateTopic(topicId: string, data: import('./types').UpdateTopicRequest): Promise<import('./types').TopicOperationProjection> {
  const res = await client.patch<import('./types').TopicOperationProjection>(`/v1/topics/${topicId}`, data, { headers: { 'Idempotency-Key': crypto.randomUUID() } });
  return res.data;
}

export async function mergeTopics(data: import('./types').MergeTopicsRequest): Promise<import('./types').TopicOperationProjection> {
  const res = await client.post<import('./types').TopicOperationProjection>('/v1/topics/merge', data, { headers: { 'Idempotency-Key': crypto.randomUUID() } });
  return res.data;
}

export async function storeTopic(topicId: string): Promise<import('./types').TopicOperationProjection> {
  const res = await client.post<import('./types').TopicOperationProjection>(`/v1/topics/${topicId}/store`, {}, { headers: { 'Idempotency-Key': crypto.randomUUID() } });
  return res.data;
}

export async function restoreTopic(topicId: string): Promise<import('./types').TopicOperationProjection> {
  const res = await client.post<import('./types').TopicOperationProjection>(`/v1/topics/${topicId}/restore`, {}, { headers: { 'Idempotency-Key': crypto.randomUUID() } });
  return res.data;
}

export async function fetchTopicRepository(params: { search?: string; ownerType?: string; page?: number; size?: number }): Promise<import('./types').TopicRepositoryPage> {
  const res = await client.get<import('./types').TopicRepositoryPage>('/v1/topic-repository', { params });
  return res.data;
}

export async function fetchTopicInboxRequests(): Promise<import('./types').TopicInboxRequestProjection[]> {
  const res = await client.get<import('./types').TopicInboxRequestProjection[]>('/v1/topic-inbox/requests');
  return res.data;
}

export async function approveTopicStore(requestId: string): Promise<import('./types').TopicOperationProjection> {
  const res = await client.post<import('./types').TopicOperationProjection>(`/v1/topic-inbox/${requestId}/approve`, {}, { headers: { 'Idempotency-Key': crypto.randomUUID() } });
  return res.data;
}

export async function rejectTopicStore(requestId: string, reason?: string): Promise<import('./types').TopicOperationProjection> {
  const res = await client.post<import('./types').TopicOperationProjection>(`/v1/topic-inbox/${requestId}/reject`, { reason: reason ?? '' }, { headers: { 'Idempotency-Key': crypto.randomUUID() } });
  return res.data;
}

export async function retryTopicGeneration(contactId: string): Promise<import('./types').TopicGenerationProjection> {
  const res = await client.post<import('./types').TopicGenerationProjection>(`/v1/contacts/${contactId}/topics/retry`);
  return res.data;
}

export async function createCallRecord(
  contactId: string | undefined,
  formData: FormData,
): Promise<{ callRecordId: string; state: string }> {
  const res = await client.post<{ callRecordId: string; state: string }>(
    contactId ? `/v1/contacts/${contactId}/call-records` : '/v1/call-records',
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

const sharedWhatsAppBase = '/v1/whatsapp';

export async function fetchSharedTemplates(params?: SharedTemplateListFilters): Promise<SharedTemplateListPage> {
  const res = await client.get<SharedTemplateListPage>(`${sharedWhatsAppBase}/templates`, { params });
  return res.data;
}

export async function fetchSharedTemplate(templateId: string, scopeId?: string): Promise<SharedTemplate> {
  const res = await client.get<SharedTemplate>(
    `${sharedWhatsAppBase}/templates/${encodeURIComponent(templateId)}`, { params: { scopeId } },
  );
  return res.data;
}

export async function createSharedTemplate(command: TemplateCommand, scopeId?: string): Promise<TemplateOperation> {
  const res = await client.post<TemplateOperation>(
    `${sharedWhatsAppBase}/templates/applications`, command, { params: { scopeId } },
  );
  return res.data;
}

export interface TemplateChangeCommand {
  changeType: import('./types').TemplateChangeType;
  expectedVersion: number;
  clientRequestId: string;
  template?: Omit<TemplateCommand, 'clientRequestId'>;
  allowSend?: boolean;
  remark?: string;
}

export async function createTemplateChangeRequest(templateId: string, command: TemplateChangeCommand, scopeId?: string): Promise<TemplateChangeOutcome> {
  const res = await client.post<TemplateChangeOutcome>(
    `${sharedWhatsAppBase}/templates/${encodeURIComponent(templateId)}/change-requests`, command, { params: { scopeId } },
  );
  return res.data;
}

export async function fetchMyTemplateChangeRequests(page = 1, size = 20): Promise<TemplateChangeRequestPage> {
  const res = await client.get<TemplateChangeRequestPage>(`${sharedWhatsAppBase}/template-change-requests/mine`, { params: { page, size } });
  return res.data;
}

export async function fetchTemplateChangeRequestsForReview(
  page = 1,
  size = 20,
  filters: { status?: string | null; search?: string } = {},
): Promise<TemplateChangeRequestPage> {
  // The review queue is filtered server-side: total then describes the filtered set.
  const status = filters.status?.trim() || undefined;
  const search = filters.search?.trim() || undefined;
  const res = await client.get<TemplateChangeRequestPage>('/v1/admin/whatsapp/template-change-requests', { params: { page, size, status, search } });
  return res.data;
}

export async function approveTemplateChangeRequest(requestId: string, clientRequestId: string): Promise<TemplateChangeOutcome> {
  const res = await client.post<TemplateChangeOutcome>(
    `/v1/admin/whatsapp/template-change-requests/${encodeURIComponent(requestId)}/approve`, { clientRequestId },
  );
  return res.data;
}

export async function rejectTemplateChangeRequest(requestId: string, reason: string): Promise<TemplateChangeRequestView> {
  const res = await client.post<TemplateChangeRequestView>(
    `/v1/admin/whatsapp/template-change-requests/${encodeURIComponent(requestId)}/reject`, { reason },
  );
  return res.data;
}

export async function retryTemplateChangeRequest(requestId: string, clientRequestId: string): Promise<TemplateChangeOutcome> {
  const res = await client.post<TemplateChangeOutcome>(
    `/v1/admin/whatsapp/template-change-requests/${encodeURIComponent(requestId)}/retry`, { clientRequestId },
  );
  return res.data;
}

export async function syncSharedTemplates(scopeId?: string): Promise<TemplateSyncResult> {
  const res = await client.post<TemplateSyncResult>(`${sharedWhatsAppBase}/templates/sync`, undefined, { params: { scopeId } });
  return res.data;
}

export async function fetchPrivateTemplates(accountId: string, params?: SharedTemplateListFilters): Promise<SharedTemplateListPage> {
  const res = await client.get<SharedTemplateListPage>(`/v1/whatsapp/business-app/accounts/${encodeURIComponent(accountId)}/templates`, { params });
  return res.data;
}

export async function fetchPrivateTemplate(accountId: string, templateId: string): Promise<SharedTemplate> {
  const res = await client.get<SharedTemplate>(`/v1/whatsapp/business-app/accounts/${encodeURIComponent(accountId)}/templates/${encodeURIComponent(templateId)}`);
  return res.data;
}

export async function createPrivateTemplate(accountId: string, command: TemplateCommand): Promise<TemplateOperation> {
  const res = await client.post<TemplateOperation>(`/v1/whatsapp/business-app/accounts/${encodeURIComponent(accountId)}/templates/applications`, command);
  return res.data;
}

export async function updatePrivateTemplate(accountId: string, templateId: string, command: TemplateUpdateCommand): Promise<TemplateOperation> {
  const res = await client.put<TemplateOperation>(`/v1/whatsapp/business-app/accounts/${encodeURIComponent(accountId)}/templates/${encodeURIComponent(templateId)}`, command);
  return res.data;
}

export async function setPrivateTemplateSendPermission(accountId: string, templateId: string, allowSend: boolean, clientRequestId = crypto.randomUUID()): Promise<TemplateOperation> {
  const res = await client.patch<TemplateOperation>(`/v1/whatsapp/business-app/accounts/${encodeURIComponent(accountId)}/templates/${encodeURIComponent(templateId)}/send-permission`, { allowSend, clientRequestId });
  return res.data;
}

export async function deletePrivateTemplate(accountId: string, templateId: string, clientRequestId = crypto.randomUUID()): Promise<TemplateOperation> {
  const res = await client.delete<TemplateOperation>(`/v1/whatsapp/business-app/accounts/${encodeURIComponent(accountId)}/templates/${encodeURIComponent(templateId)}`, { params: { clientRequestId } });
  return res.data;
}

export async function syncPrivateTemplates(accountId: string): Promise<TemplateSyncResult> {
  const res = await client.post<TemplateSyncResult>(`/v1/whatsapp/business-app/accounts/${encodeURIComponent(accountId)}/templates/sync`);
  return res.data;
}

export async function fetchPrivateTemplateOperations(accountId: string, templateId: string): Promise<TemplateOperation[]> {
  const res = await client.get<TemplateOperation[]>(`/v1/whatsapp/business-app/accounts/${encodeURIComponent(accountId)}/templates/${encodeURIComponent(templateId)}/operations`);
  return res.data;
}

export async function uploadTemplateMedia(
  accountOrFormat: string,
  formatOrFile: TemplateMediaFormat | File,
  fileOrRequestId: File | string,
  requestIdOrSignal?: string | AbortSignal,
  signal?: AbortSignal,
  scopeId?: string,
): Promise<TemplateMediaAsset> {
  const sharedCall = typeof formatOrFile !== 'string';
  const format = (sharedCall ? accountOrFormat : formatOrFile) as TemplateMediaFormat;
  const file = (sharedCall ? formatOrFile : fileOrRequestId) as File;
  const clientRequestId = (sharedCall ? fileOrRequestId : requestIdOrSignal) as string;
  const requestSignal = sharedCall ? requestIdOrSignal as AbortSignal | undefined : signal;
  const formData = new FormData();
  formData.append('format', format);
  formData.append('file', file);
  formData.append('clientRequestId', clientRequestId);
  const params = { scopeId };
  const res = requestSignal
    ? await client.post<TemplateMediaAsset>(`${sharedWhatsAppBase}/template-media`, formData, { params, signal: requestSignal })
    : await client.post<TemplateMediaAsset>(`${sharedWhatsAppBase}/template-media`, formData, { params });
  return res.data;
}

export async function fetchTemplateMediaUpload(
  accountOrRequestId: string,
  requestIdOrSignal?: string | AbortSignal,
  signal?: AbortSignal,
  scopeId?: string,
): Promise<TemplateMediaAsset> {
  const clientRequestId = typeof requestIdOrSignal === 'string' ? requestIdOrSignal : accountOrRequestId;
  const requestSignal = typeof requestIdOrSignal === 'string' ? signal : requestIdOrSignal;
  const encoded = encodeURIComponent(clientRequestId);
  const params = { scopeId };
  const res = requestSignal
    ? await client.get<TemplateMediaAsset>(`${sharedWhatsAppBase}/template-media/uploads/${encoded}`, { params, signal: requestSignal })
    : await client.get<TemplateMediaAsset>(`${sharedWhatsAppBase}/template-media/uploads/${encoded}`, { params });
  return res.data;
}

export async function fetchTemplateOperations(
  templateIdOrAccountId: string,
  templateCode?: string,
  language?: string,
  scopeId?: string,
): Promise<TemplateOperation[]> {
  const templateId = templateCode ?? templateIdOrAccountId;
  const res = await client.get<TemplateOperation[]>(
    `${sharedWhatsAppBase}/templates/${encodeURIComponent(templateId)}/operations`,
    { params: { language: templateCode ? language : undefined, scopeId } },
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

export async function fetchPublicTemplates(
  query: PublicTemplateQuery = {},
  scopeId?: string,
): Promise<PublicTemplateListPage> {
  const res = await client.get<PublicTemplateListPage>(`${sharedWhatsAppBase}/public-templates`, {
    params: { ...boundedPublicTemplateQuery(query), scopeId },
    paramsSerializer: { indexes: null },
  });
  return res.data;
}

// ---------- AI 助手 ----------
//
// 三个端点都不带身份字段：用户身份只来自服务端的认证上下文（SecurityUtil.currentUserId()）。
// 请求体里塞 userId 也不会被读取 —— 这是设计文档 §8.1 的「铁律」在客户端的体现。

const assistantBase = '/assistant';

/**
 * 一轮对话：服务端解析 → 追问 / 直接执行 / 落待确认，前端只按返回的 `kind` 分支渲染。
 *
 * <h2>这是唯一一条流式端点</h2>
 * 它没有非流式的版本：服务端边跑边把进度写回来，`onDelta` 拿到的是一段**增量**
 * （不是累计文本），真正的结论只认最后那一帧 `final` —— 也就是这个函数的返回值。
 *
 * <p>所以它**不走 axios**（axios 读不到增量）。传输细节在 `assistantStream.ts`，
 * 这里保留这一层是为了让「助手这一组端点在哪儿」这个问题只有一个答案。
 */
export async function sendAssistantMessage(
  request: AssistantMessageRequest,
  handlers?: AssistantStreamHandlers,
): Promise<AssistantTurnResult> {
  return streamAssistantMessage(request, handlers);
}

/**
 * 确认一条待确认动作。
 *
 * 只传 `pendingActionId`，不回传工具名与参数：参数以服务端落库的那一份为准，并在执行前重新校验。
 * 若允许前端回传参数，确认就退化成「前端说了算」，而把授权落库这件事就白做了。
 */
export async function confirmAssistantAction(
  pendingActionId: string,
): Promise<AssistantTurnResult> {
  const res = await client.post<AssistantTurnResult>(
    `${assistantBase}/actions/${encodeURIComponent(pendingActionId)}/confirm`,
  );
  return res.data;
}

/** 取消一条待确认动作，什么都不会执行。 */
export async function cancelAssistantAction(
  pendingActionId: string,
): Promise<AssistantTurnResult> {
  const res = await client.post<AssistantTurnResult>(
    `${assistantBase}/actions/${encodeURIComponent(pendingActionId)}/cancel`,
  );
  return res.data;
}

/**
 * 回放某个会话的历史消息，时间正序。面板打开时用它接上上次的对话。
 *
 * 归属校验在服务端按登录用户做，前端不需要（也无法）提供身份。
 */
export async function fetchAssistantConversation(
  conversationId: string,
): Promise<AssistantConversationMessage[]> {
  const res = await client.get<AssistantConversationMessage[]>(
    `${assistantBase}/conversations/${encodeURIComponent(conversationId)}/messages`,
  );
  return res.data;
}

/**
 * 问服务端「我上次在哪个会话里」。
 *
 * 它**不是**「新建会话」的替代品：会话号仍然由前端生成并保存（见 `useAssistant`），
 * 这个端点只在本地那个号什么都读不出来时兜底 —— 清过浏览器数据、换了设备、换了账号，
 * 记录一直在库里，丢的只是那个号。
 *
 * @returns `conversationId` 为 `null` 表示「服务端明确说还没有任何对话」，不是失败。
 */
export async function fetchLatestAssistantConversation(): Promise<
  import('./types').AssistantLatestConversation
> {
  const res = await client.get<import('./types').AssistantLatestConversation>(
    `${assistantBase}/conversations/latest`,
  );
  return res.data;
}
