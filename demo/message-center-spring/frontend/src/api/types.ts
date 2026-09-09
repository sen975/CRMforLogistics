export interface ContactIdentityResponse {
  id: string;
  channelType: string;
  identityScope: string;
  identityValue: string;
  displayName: string;
}

export interface ContactTag {
  id: string;
  name: string;
  color?: string | null;
}

export type ChannelAddressBookChannel = 'chatapp' | 'email' | 'phone';

export interface ChannelAddressBookItem {
  contactId: string;
  identityId: string;
  displayName: string;
  remark: string | null;
  channelType: ChannelAddressBookChannel;
  address: string;
  channelDisplayName: string;
  additionalChannelTypes: ChannelAddressBookChannel[];
  source: 'manual' | 'synced' | string;
  lastContactAt: string | null;
  hasActivity: boolean;
  canDelete: boolean;
}

export interface ChannelAddressBookPageResponse {
  items: ChannelAddressBookItem[];
  page: number;
  size: number;
  hasMore: boolean;
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
  tags?: ContactTag[];
  identities: ContactIdentityResponse[];
}

export interface ContactConversationItem {
  type: 'CONTACT';
  id: string;
  displayName: string;
  remark?: string | null;
  avatarUrl?: string | null;
  channelTypes: string[];
  lastMessageAt: string | null;
  lastText: string;
  messageCount: number;
  unreadCount: number;
  pinned?: boolean;
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
  pinned?: boolean;
}

export type ConversationListItem = ContactConversationItem | WeComGroupConversationItem;

export interface ConversationPage {
  records: ConversationListItem[];
  total: number;
  size: number;
  current: number;
  pages: number;
  nextCursor?: string | null;
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

export interface RelatedWeComGroupResponse {
  sourceConversationId: string;
  displayName: string;
  avatarUrl?: string | null;
  participantCount: number;
}

export interface WeComThreadResponse {
  contactId: string;
  sourceConversationIds: string[];
  relatedGroups: RelatedWeComGroupResponse[];
  items: MessageResponse[];
  nextCursor: string | null;
  messageCount: number;
  threadRevision: string;
}

export interface WeComGroupThreadResponse {
  sourceConversationId: string;
  groupChatId: string | null;
  displayName: string;
  avatarUrl?: string | null;
  openClientUrl?: string | null;
  participants: WeComPartyView[];
  items: MessageResponse[];
  nextCursor: string | null;
  messageCount: number;
  threadRevision: string;
}

export interface WeComGroupNameRefreshResponse {
  id: string;
  sourceConversationId: string;
  triggerSource: string;
  status: string;
  createdAt: string | null;
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

export interface RegisterRequest {
  username: string;
  displayName?: string;
  password: string;
}

export interface AccountProfile {
  id: string;
  username: string;
  displayName: string;
  roles: string[];
  avatar: {
    source: 'WECOM' | 'UPLOAD' | 'INITIAL';
    contentUrl: string | null;
    initial: string;
    revision: string | null;
  };
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export interface AdminUser {
  id: string;
  username: string;
  displayName: string;
  status: string;
  roles: string[];
  createdAt: string;
}

export interface AdminUserPage {
  items: AdminUser[];
  total: number;
  page: number;
  size: number;
}

export interface AccountRole {
  code: string;
  displayName: string;
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
  wecomDisplayName?: string | null;
  corpName?: string | null;
}

export type WeComAvatarAuthorizationStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED';

export interface WeComAvatarAuthorizationAttempt {
  authorizationId: string;
  authorizationUrl: string;
  status: WeComAvatarAuthorizationStatus;
  expiresIn: number;
}

export interface WeComAvatarAuthorizationProjection {
  authorizationId: string;
  status: WeComAvatarAuthorizationStatus;
  errorCode: string | null;
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
  | { targetType: 'WECOM_GROUP'; targetId: string; chatId?: string | null };

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

export type ConversationTargetType = 'CONTACT' | 'WECOM_GROUP';

export interface ConversationOrderRequest {
  sourceType: ConversationTargetType;
  sourceId: string;
  targetType: ConversationTargetType;
  targetId: string;
  placement: 'BEFORE' | 'AFTER';
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
  onboardingMode?: 'BUSINESS_APP_COEXISTENCE' | 'API_ONLY' | null;
  providerScopeId?: string | null;
}

export interface WhatsAppAuthorizationAttempt {
  attemptId: string;
  state: string;
  expiresAt: string;
}

export interface WhatsAppAuthorizationResult {
  accountId: string;
  phoneNumberLast4: string;
  onboardingMode: 'BUSINESS_APP_COEXISTENCE' | 'API_ONLY';
}

export interface WhatsAppPhoneNumberStatus {
  accountId: string;
  phoneNumberLast4: string;
  ownerUserId: string | null;
  onboardingMode: 'BUSINESS_APP_COEXISTENCE' | 'API_ONLY' | null;
  phoneVerificationStatus: string | null;
  providerPhoneStatus: string | null;
}

export interface WhatsAppPhoneOperationStatus {
  operationId: string;
  phoneNumberLast4: string;
  status: string;
}

export interface WhatsAppCapabilityStatus {
  ready: boolean;
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

export interface CreateChannelAccountRequest {
  channelType: 'chatapp' | 'email';
  name: string;
  accountIdentifier: string;
  credentials: ChannelCredentials;
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

export type TopicGenerationStatus = 'NOT_STARTED' | 'GENERATING' | 'READY' | 'FAILED';
export interface TopicSourceItem {
  id: string;
  sourceType: 'MESSAGE' | 'CALL_RECORD' | 'WECOM_SUMMARY';
  occurredAt: string;
  channelType: 'chatapp' | 'email' | 'phone' | 'wecom';
}
export interface TopicProjection {
  id: string;
  contactId?: string;
  title: string;
  summary: string;
  summarySource: 'AI' | 'EMPLOYEE';
  firstOccurredAt: string;
  lastOccurredAt: string;
  channels: string[];
  sourceCount: number;
  sourceItems: TopicSourceItem[];
  version: number;
  contactName?: string | null;
  contactRemark?: string | null;
  contactChannelType?: string | null;
  contactChannelNickname?: string | null;
  ownerType?: 'CONTACT' | 'WECOM_GROUP';
  ownerId?: string | null;
  ownerLabel?: string | null;
  isReferencedGroupTopic?: boolean;
  reviewOrigin?: 'MERGE_SOURCE' | 'SPLIT_SOURCE' | 'MANUAL_SELECTION' | null;
  reviewSourceTopicTitle?: string | null;
}
export type TopicOperationKind = 'EDIT' | 'MERGE' | 'STORE' | 'RESTORE';
export interface TopicOperationProjection {
  id: string;
  kind: TopicOperationKind;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  errorCode: string | null;
  createdAt: string | null;
  completedAt: string | null;
}
export interface TopicGenerationProjection {
  status: TopicGenerationStatus;
  jobId: string | null;
  errorCode: string | null;
  updatedAt: string | null;
}
export interface TopicTimelineResponse {
  generation: TopicGenerationProjection;
  topics: TopicProjection[];
  weComUnsupported: boolean;
}
export interface ManualReviewSourceOption {
  id: string;
  contactIdentityId: string;
  sourceType: 'MESSAGE' | 'CALL_RECORD' | 'WECOM_SUMMARY';
  channelType: string;
  occurredAt: string;
  direction: string;
  subject: string;
  text: string;
  selectable: boolean;
  excludedReason?: string | null;
  assignedTopicId?: string | null;
  assignedTopicTitle?: string | null;
}
export interface ManualReviewSourceListResponse {
  contactId: string;
  items: ManualReviewSourceOption[];
  hasMore: boolean;
  wecomExcludedReason?: string | null;
}
export interface ManualReviewPreviewResponse {
  previewId: string;
  contactId: string;
  sourceFingerprint: string;
  assignments: ManualReviewAssignment[];
  expectedVersions: Record<string, number>;
  expiresAt: string;
}
export interface ManualReviewAssignment {
  topicKey: string;
  title: string;
  summary: string;
  relevance: number;
  sourceIds: string[];
}
export interface ManualReviewApplyResponse { previewId: string; topicIds: string[]; applied: boolean }
export interface TopicFusionPreviewResponse {
  previewId: string;
  topicIds: string[];
  title: string;
  summary: string;
  sourceCount: number;
  expectedVersions: Record<string, number>;
  expiresAt: string;
}

export interface ContactTopicsResponse extends TopicTimelineResponse {
  contactId: string;
}

export interface WeComGroupTopicsResponse extends TopicTimelineResponse {
  sourceConversationId: string;
}
export interface UpdateTopicRequest {
  contactId: string;
  title: string;
  confirmedSummary: string;
  expectedVersion: number;
}
export interface MergeTopicsRequest {
  contactId: string;
  topicIds: string[];
  expectedVersions: Record<string, number>;
}

export interface TopicFusionPreviewRequest {
  topicIds: string[];
  expectedVersions: Record<string, number>;
}

export interface TopicInboxRequestProjection {
  id: string;
  topicId: string;
  topicTitle: string;
  ownerType: 'CONTACT' | 'WECOM_GROUP';
  ownerId: string;
  ownerLabel: string;
  requestedByUserId: string;
  createdAt: string;
}

export interface TopicRepositoryPage extends MyBatisPage<TopicProjection> {}

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
  version: number;
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

// Shared WhatsApp catalog projections. Account-scoped types above remain for
// legacy component fixtures, while all runtime template APIs use these types.
export interface SharedTemplate {
  id: string;
  version: number;
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
  components: TemplateComponent[];
  examples: Record<string, string[]>;
  messageSendTtlSeconds: number | null;
  qualityScore: string | null;
  providerUpdatedAt: string | null;
  lastSyncedAt: string | null;
  deletedAt: string | null;
}

export interface SharedTemplateListPage {
  items: SharedTemplate[];
  total: number;
  page: number;
  size: number;
}

export interface SharedTemplateListFilters {
  page?: number;
  size?: number;
  search?: string;
  status?: SharedTemplate['reviewStatus'];
  category?: string;
  language?: string;
  allowSend?: boolean;
  deleted?: boolean;
}

export type TemplateChangeType = 'MODIFY' | 'SET_SEND_PERMISSION' | 'DELETE' | 'BIND_MEDIA';
export type TemplateChangeStatus =
  | 'PENDING_APPROVAL' | 'REJECTED' | 'STALE'
  | 'EXECUTING' | 'SUCCEEDED' | 'EXECUTION_FAILED';

export interface TemplateChangeRequestView {
  id: string;
  templateId: string;
  templateDisplayName: string;
  baseVersion: number;
  changeType: TemplateChangeType;
  status: TemplateChangeStatus;
  diffs: Array<{ field: string; label: string; beforeValue: unknown; afterValue: unknown }>;
  requestedByDisplayName: string;
  reviewedByDisplayName: string | null;
  reviewReason: string | null;
  executionErrorCode: string | null;
  executionErrorMessage: string | null;
  providerRequestId: string | null;
  createdAt: string;
  reviewedAt: string | null;
  executionCompletedAt: string | null;
}

export interface TemplateChangeRequestPage {
  items: TemplateChangeRequestView[];
  total: number;
  page: number;
  size: number;
}

export interface TemplateChangeOperation {
  operationId: string;
  operationType: TemplateOperation['operationType'];
  operationStatus: TemplateOperation['operationStatus'];
  templateCode: string;
  providerRequestId: string | null;
  errorCode: string | null;
}

export interface TemplateChangeOutcome {
  mode: 'APPROVAL_REQUIRED' | 'DIRECT';
  request: TemplateChangeRequestView | null;
  operation: TemplateChangeOperation | null;
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
