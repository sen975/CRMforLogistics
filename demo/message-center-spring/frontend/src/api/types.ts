export interface ContactIdentityResponse {
  id: string;
  channelType: string;
  identityScope: string;
  identityValue: string;
  displayName: string;
}

export interface ContactResponse {
  id: string;
  displayName: string;
  remark: string;
  channelTypes: string[];
  lastMessageAt: string | null;
  lastText: string;
  messageCount: number;
  unreadCount: number;
  identities: ContactIdentityResponse[];
}

export interface ContactConversationItem {
  type: 'CONTACT';
  id: string;
  displayName: string;
  avatarUrl?: string | null;
  channelTypes: string[];
  lastMessageAt: string | null;
  lastText: string;
  messageCount: number;
  unreadCount: number;
}

export interface WeComGroupConversationItem {
  type: 'WECOM_GROUP';
  id: string;
  displayName: string;
  avatarUrl?: string | null;
  channelTypes: string[];
  lastMessageAt: string | null;
  lastText: string;
  messageCount: number;
  unreadCount: number;
  providerConversationKey: string | null;
  participantCount: number;
}

export type ConversationListItem = ContactConversationItem | WeComGroupConversationItem;

export interface ConversationPage {
  records: ConversationListItem[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface MessageResponse {
  id: string;
  sourceId?: string | null;
  sourceConversationId?: string | null;
  conversationType?: 'DIRECT' | 'GROUP' | null;
  conversationDisplayName?: string | null;
  direction: string;
  kind: string;
  subject: string;
  bodyText: string;
  bodyHtml: string;
  channelType: string;
  from: string;
  to: string;
  occurredAt: string;
  status: string;
  ingestSequence: number;
  attachments: MessageAttachmentResponse[];
  sender?: WeComPartyView | null;
}

export interface WeComPartyView {
  partyId: string;
  partyType: 'EMPLOYEE' | 'EXTERNAL_CONTACT' | 'ROBOT' | string;
  providerPartyId: string;
  displayName: string;
  avatarUrl?: string | null;
  contactId?: string | null;
  contactAccessible: boolean;
  isCurrentViewer: boolean;
}

export interface WeComThreadResponse {
  contactId: string;
  sourceConversationIds: string[];
  items: MessageResponse[];
  nextCursor: string | null;
  messageCount: number;
  threadRevision: string;
}

export interface WeComGroupThreadResponse {
  sourceConversationId: string;
  providerConversationKey: string;
  displayName: string;
  avatarUrl?: string | null;
  openClientUrl?: string | null;
  participants: WeComPartyView[];
  items: MessageResponse[];
  nextCursor: string | null;
  messageCount: number;
  threadRevision: string;
}

export interface MessageAttachmentResponse {
  id: string;
  mediaKind: string;
  mimeType: string;
  fileName: string;
  sizeBytes: number;
}

export interface ThreadResponse {
  items: MessageResponse[];
  nextCursor: string;
  messageCount: number;
  threadRevision: string;
}

export interface TemplateResponse {
  templateCode: string;
  templateName: string;
  displayName: string;
  languageCode: string;
  body: string;
  placeholders: string[];
  category?: string | null;
  components?: unknown;
  variableDefinitions?: Record<string, string[]>;
}

export type ChatAppBroadcastStatus =
  | 'DRAFT'
  | 'QUEUED'
  | 'SUBMITTING'
  | 'SUBMITTED'
  | 'RECONCILING'
  | 'SUCCEEDED'
  | 'PARTIALLY_FAILED'
  | 'FAILED'
  | 'SUBMISSION_UNKNOWN'
  | 'STATUS_UNKNOWN'
  | 'CANCELLED';

export interface ChatAppBroadcast {
  id: string;
  channelAccountId: string;
  name: string;
  templateCode: string;
  templateName: string;
  languageCode: string;
  recipientCount: number;
  successCount: number;
  failedCount: number;
  processingCount: number;
  status: ChatAppBroadcastStatus;
  providerGroupMessageId: string | null;
  providerRequestId: string | null;
  providerCode: string | null;
  lastReconciliationRequestId: string | null;
  lastReconciliationProviderCode: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  retriesBroadcastId: string | null;
  createdByUserId: string;
  submittedAt: string | null;
  reconciledAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ChatAppBroadcastPage {
  records: ChatAppBroadcast[];
  total: number;
  page: number;
  size: number;
}

export interface ChatAppBroadcastRecipient {
  id: string;
  contactId: string;
  contactIdentityId: string;
  recipientName: string;
  maskedNumber: string;
  templateParams: Record<string, string>;
  messageId: string | null;
  providerMessageId: string | null;
  providerUniqueMessageId: string | null;
  status: 'QUEUED' | 'PROCESSING' | 'SENT' | 'DELIVERED' | 'READ' | 'FAILED_RECIPIENT';
  failureReason: string | null;
  providerSentAt: string | null;
  lastReconciledAt: string | null;
}

export interface ChatAppBroadcastDetail {
  broadcast: ChatAppBroadcast;
  recipients: ChatAppBroadcastRecipient[];
  reconciliation: {
    evidenceRows: number;
    matchedRows: number;
    unmatchedRows: number;
    processingRecipients: number;
    latestDiagnosticCode: string | null;
  };
}

export interface ChatAppBroadcastRecipientPage {
  records: ChatAppBroadcastRecipient[];
  total: number;
  page: number;
  size: number;
}

export interface CreateChatAppBroadcastCommand {
  channelAccountId: string;
  name: string;
  templateCode: string;
  languageCode: string;
  clientRequestId: string;
  recipients: Array<{
    contactIdentityId: string;
    templateParams: Record<string, string>;
  }>;
  sharedTemplateParams: Record<string, string>;
}

export interface LoginResponse {
  token: string;
  username: string;
  roles: string[];
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface WeComLoginAttempt {
  loginType: string;
  appId: string;
  agentId: string;
  redirectUri: string;
  state: string;
  expiresIn: number;
}

export interface WeComLoginResponse extends LoginResponse {
  viewerAuthToken: string | null;
  viewerExpiresIn: number;
}

export interface WeComBindingResponse {
  userId: string;
  authCorpId: string | null;
  wecomUserId: string | null;
  provisioningSource: 'AUTO_CREATED' | 'BOUND_EXISTING' | null;
  bound: boolean;
}

export interface WeComViewerBootstrapResponse {
  wecomUserId: string;
  viewerAuthToken: string;
  expiresIn: number;
}

export interface WeComViewerSessionResponse {
  viewerSessionId: string;
  expiresIn: number;
}

export type WeComViewerTarget =
  | { targetType: 'CONTACT'; targetId: string }
  | { targetType: 'WECOM_GROUP'; targetId: string };

export interface WeComViewerMessage {
  msgid: string;
  secretKey: string;
}

export interface WeComViewerSessionDetail {
  viewerSessionId: string;
  corpId: string;
  agentId: string;
  messages: WeComViewerMessage[];
}

export interface WeComSignatureBundle {
  timestamp: string;
  nonceStr: string;
  signature: string;
}

export interface WeComJsSdkConfig {
  corpId: string;
  agentId: string;
  jsApiList: string[];
  configSignature: WeComSignatureBundle;
  agentConfigSignature: WeComSignatureBundle;
}

export interface WeComInstallationSummary {
  authCorpId: string;
  corpName?: string | null;
  agentId: string;
  authStatus: string;
  authorizedAt: string | null;
}

export type WeComProviderData = Record<string, unknown>;

export interface WeComCursorPage<T = WeComProviderData> {
  items: T[];
  nextCursor?: string | null;
  hasMore?: boolean | number;
  [key: string]: unknown;
}

export interface ApiError {
  code: string;
  message: string;
  traceId: string;
  fieldErrors: Record<string, string>;
}

export interface ChannelCapability {
  channelType: string;
  channelAccountId: string;
  displayName: string;
  authStatus: string;
}

export interface MyBatisPage<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface ChannelAccount {
  id: string;
  channelType: string;
  name: string;
  accountIdentifier: string;
  authStatus: string;
  syncStatus: string;
  lastSyncedAt: string | null;
  createdAt: string;
}

export interface WeComSendRequest {
  corpId: string;
  agentId: string;
  to: string;
  text: string;
}

export interface ChannelCredentials {
  [key: string]: string;
}

export interface SyncResult {
  messageSync?: { pages: number; fetched: number; saved: number; durationMs: number };
  templateSync?: { pages: number; fetched: number; changed: number; durationMs: number };
  channel?: string;
  fetched?: number;
  saved?: number;
  skipped?: number;
  message?: string;
  error?: string;
}

export interface CallRecordResponse {
  id: string;
  contactAnchorPointId: string;
  phonePointId: string;
  direction: string;
  occurredAt: string;
  createdAt: string;
  createdBy: string;
  clientRequestId: string;
  note: string;
  audio: {
    originalFileName: string;
    sizeBytes: number;
    sha256: string;
    contentType: string;
    durationSeconds: number;
  };
  transcription: {
    state: string;
    model: string;
    attempts: number;
    nextAttemptAt?: string;
    result?: {
      model: string;
      durationSeconds: number;
      originalText: string;
      segments: TranscriptSegment[];
      completedAt: string;
    };
    error?: {
      code: string;
      message: string;
      retryable: boolean;
    };
  };
  revisions: TranscriptRevision[];
  currentRevisionId: string | null;
  version: number;
}

export interface TranscriptSegment {
  startSeconds: number;
  endSeconds: number;
  text: string;
}

export interface TranscriptRevision {
  id: string;
  text: string;
  editedAt: string;
  editedBy: string;
}

export interface TimelineItem {
  type: string;
  occurredAt: string;
  sortId: string;
  payload: Record<string, unknown>;
}

export interface TimelineResponse {
  items: TimelineItem[];
  nextCursor: string;
  itemCount: number;
  threadRevision: string;
}

export interface PhoneRecordResponse {
  id: string;
  contactId: string;
  contactAnchorPointId: string;
  contactDisplayName: string;
  phonePointId: string;
  occurredAt: string;
  direction: string;
  durationSeconds: number;
  note: string;
  transcriptionState: string;
  errorCode: string;
  errorMessage: string;
  errorRetryable: boolean;
  transcriptionAttempts: number;
  clientRequestId: string;
  version: number;
}

export interface PhoneRecordPage {
  items: PhoneRecordResponse[];
  nextCursor: string;
  totalCount: number;
}

export type TemplateComponentType = 'HEADER' | 'BODY' | 'FOOTER' | 'BUTTONS';
export type TemplateHeaderFormat = 'TEXT' | 'IMAGE' | 'VIDEO' | 'DOCUMENT';
export type TemplateMediaFormat = 'IMAGE' | 'VIDEO' | 'DOCUMENT';
export type TemplateMediaAssetStatus =
  | 'PROCESSING' | 'UPLOADED' | 'FAILED' | 'SUBMISSION_UNKNOWN'
  | 'ATTACHED' | 'ATTACHMENT_UNKNOWN' | 'ORPHANED';
export type TemplateButtonType = 'QUICK_REPLY' | 'URL' | 'PHONE_NUMBER';
export type TemplateCategory = 'UTILITY' | 'MARKETING';

export interface TemplateButton {
  type: TemplateButtonType;
  text: string | null;
  url: string | null;
  phoneNumber: string | null;
}

export interface TemplateComponent {
  type: TemplateComponentType;
  headerFormat: TemplateHeaderFormat | null;
  text: string | null;
  mediaAssetId: string | null;
  buttons: TemplateButton[];
}

export interface TemplateAdmin {
  id: string;
  accountId: string;
  templateCode: string;
  name: string;
  remark: string | null;
  displayName: string;
  language: string;
  category: string | null;
  reviewStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED' | 'UNKNOWN';
  providerAuditStatus: string | null;
  rejectionReason: string | null;
  allowSend: boolean;
  desiredAllowSend: boolean;
  permissionSyncStatus: 'IDLE' | 'PENDING' | 'FAILED';
  permissionSyncError: string | null;
  components: TemplateComponent[];
  examples: Record<string, string[]>;
  messageSendTtlSeconds: number | null;
  qualityScore: string | null;
  providerUpdatedAt: string | null;
  lastSyncedAt: string | null;
  deletedAt: string | null;
}

export interface TemplateOperation {
  operationId: string;
  operationType: 'CREATE' | 'MODIFY' | 'SET_SEND_PERMISSION' | 'DELETE' | 'RECONCILE' | 'RETIRED';
  operationStatus: 'PROCESSING' | 'SUCCEEDED' | 'SUBMISSION_UNKNOWN' | 'FAILED';
  templateCode: string;
  language: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  traceId: string | null;
  actorUserId: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface TemplateMediaAsset {
  id: string;
  clientRequestId: string;
  format: TemplateMediaFormat;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  providerUrl: string | null;
  assetStatus: TemplateMediaAssetStatus;
  errorCode: string | null;
  errorMessage: string | null;
  traceId: string | null;
}

export interface TemplateListPage {
  items: TemplateAdmin[];
  total: number;
  page: number;
  size: number;
}

export interface TemplateCommand {
  name: string;
  language: string;
  category: TemplateCategory;
  components: TemplateComponent[];
  examples: Record<string, string[]>;
  messageSendTtlSeconds?: number | null;
  clientRequestId: string;
}

export type TemplateUpdateCommand = Omit<TemplateCommand, 'language'>;

export interface TemplateListFilters {
  page?: number;
  size?: number;
  search?: string;
  status?: TemplateAdmin['reviewStatus'];
  category?: string;
  language?: string;
  allowSend?: boolean;
  deleted?: boolean;
}

export interface TemplateSyncResult {
  pages: number;
  fetched: number;
  changed: number;
  complete: boolean;
}

export interface PublicTemplateVariable {
  code: string;
  name: string | null;
  example: string | null;
  format: string | null;
}

export interface PublicTemplateButton {
  name: string | null;
  type: string | null;
  url: string | null;
}

export interface PublicTemplatePage {
  name: string | null;
  text: string | null;
  buttons: PublicTemplateButton[];
}

export interface PublicTemplateContent {
  templateName: string | null;
  sceneTemplateName: string | null;
  externalTemplateCode: string | null;
  languageCode: string | null;
  category: string | null;
  pages: PublicTemplatePage[];
  variables: PublicTemplateVariable[];
}

export interface PublicTemplate {
  code: string;
  name: string;
  language: string;
  category: string | null;
  industries: string[];
  usecase: string | null;
  topic: string | null;
  content: PublicTemplateContent;
}

export interface PublicTemplateListPage {
  items: PublicTemplate[];
  total: number;
  page: number;
  size: number;
}

export interface PublicTemplateQuery {
  name?: string;
  language?: string;
  category?: string;
  industries?: string[];
  usecases?: string[];
  page?: number;
  size?: number;
}
