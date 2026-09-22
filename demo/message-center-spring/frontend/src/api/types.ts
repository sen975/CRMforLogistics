export type SearchMode = 'contact' | 'tag';

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

export type ContactMemoryState = 'CLEAN' | 'DIRTY' | 'PROCESSING' | 'RETRY_WAIT' | 'FAILED' | string;

export interface ContactMemoryProfile {
  id: string;
  version: number;
  content: string;
  createdAt: string;
}

export interface ContactMemoryAiTag {
  id: string;
  name: string;
  category: string;
  colorToken: string | null;
  status: 'ACTIVE' | 'STALE' | string;
  confidence: number;
}

export interface ContactMemoryResponse {
  profile: ContactMemoryProfile | null;
  humanTags: ContactTag[];
  aiTags: ContactMemoryAiTag[];
  state: ContactMemoryState;
  lastSuccessAt: string | null;
  lastFailureCode: string | null;
  pendingInbound: boolean;
  aiTagsNextCursor?: string | null;
  aiTagsHasMore?: boolean;
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
  matchedTags?: string[];
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
  memory?: ContactMemoryResponse | null;
  matchedTags?: string[];
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
  matchedTags?: string[];
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

/**
 * Server-owned mapping from a 企业微信 external contact id to the CRM contact this account
 * may open. `contactId` is null when the external contact has no CRM contact yet, and
 * `accessible` mirrors the conversation accessibility rule.
 */
export interface WeComExternalContactLink {
  externalUserId: string;
  contactId: string | null;
  identityId: string | null;
  accessible: boolean;
}

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
  startupProfile?: { appId: string; configId: string; onboardingMode: string } | null;
}

export interface WhatsAppAuthorizationResult {
  accountId: string;
  phoneNumberLast4: string;
  onboardingMode: 'BUSINESS_APP_COEXISTENCE' | 'API_ONLY';
}

export interface WhatsAppPhoneOperationStatus {
  operationId: string;
  accountId?: string | null;
  phoneNumberLast4: string;
  status: string;
}

export interface WhatsAppPhoneOperationProjection {
  operationId: string;
  accountId: string | null;
  phoneNumberLast4: string;
  accountName: string | null;
  accountRemark: string | null;
  status: string;
}

export interface WhatsAppAccountProjection {
  accountId: string;
  mode: 'BUSINESS_APP_COEXISTENCE' | 'API_ONLY' | null;
  name: string;
  remark: string | null;
  maskedPhone: string;
  providerStatus: string | null;
  verificationStatus: string | null;
  templateDomain: string;
  recoverableError: string | null;
}

export interface WhatsAppHistorySyncProjection {
  jobId: string | null;
  accountId: string;
  status: string;
}

export interface WhatsAppCapabilityStatus {
  ready: boolean;
}

export interface AdminWhatsAppAccountProjection {
  accountId: string;
  ownerUserId: string | null;
  maskedPhone: string;
  name: string;
  providerStatus: string | null;
  verificationStatus: string | null;
  version: number;
}

/**
 * One number CAMS answered for, in CAMS's own words. The statuses reach the page unmapped, because a
 * number that is not ACTIVE is never imported and this is the only place that says why.
 */
export interface AdminWhatsAppProviderPhoneReport {
  maskedPhone: string;
  providerStatus: string | null;
  verificationStatus: string | null;
  /** True once the number is ACTIVE and therefore imported or refreshed. */
  accepted: boolean;
}

export interface AdminWhatsAppSyncResult {
  importedCount: number;
  refreshedCount: number;
  unavailableCount: number;
  providerPhones: AdminWhatsAppProviderPhoneReport[];
  accounts: Array<Omit<AdminWhatsAppAccountProjection, 'version'> & {
    lastSyncedAt: string | null;
  }>;
}

export interface AdminCamsScope {
  scopeId: string;
  configured: boolean;
  displayName: string;
  custSpaceId: string;
  accessKeyIdMasked: string;
  region: string;
  endpoint: string;
  status: string;
  version: number;
  lastTestedAt: string | null;
  lastTestStatus: string | null;
  lastTestErrorCode: string | null;
  lastSyncedAt: string | null;
  lastSyncStatus: string | null;
  lastSyncErrorCode: string | null;
  source: string;
  /** A Business App space is the one an employee authorized and needs no administrator approval. */
  scopeType: AdminCamsScopeType;
  ownerUserId: string | null;
}

export type AdminCamsScopeType = 'ENTERPRISE_API' | 'EMPLOYEE_BUSINESS_APP';

export interface AdminWhatsAppScopeOverview {
  scopeId: string;
  displayName: string;
  custSpaceId: string;
  status: string;
  usableAccountCount: number;
  pendingApprovalCount: number;
  lastTestedAt: string | null;
  lastTestStatus: string | null;
  lastTestErrorCode: string | null;
  lastSyncedAt: string | null;
  lastSyncStatus: string | null;
  lastSyncErrorCode: string | null;
}

export interface AdminWhatsAppOverview {
  scopes: AdminWhatsAppScopeOverview[];
  totalPendingApprovalCount: number;
}

export interface AdminCamsRequest {
  displayName?: string;
  custSpaceId: string;
  accessKeyId: string;
  accessKeySecret?: string;
  region: string;
  endpoint: string;
  expectedVersion?: number;
  scopeType?: AdminCamsScopeType;
  /** Required when scopeType is EMPLOYEE_BUSINESS_APP: the space belongs to this employee. */
  ownerUserId?: string | null;
}

export interface AdminScopedWhatsAppAccount extends AdminWhatsAppAccountProjection {
  lastSyncedAt?: string | null;
}

export interface AdminWhatsAppAssignmentRequest {
  targetOwnerId: string;
  reason: string;
  expectedVersion: number;
}

export interface AdminWhatsAppVersionedReasonRequest {
  reason: string;
  expectedVersion: number;
}

export interface AdminWhatsAppAssignmentAuditProjection {
  auditId: string;
  previousOwnerUserId: string | null;
  nextOwnerUserId: string | null;
  actorUserId: string;
  action: 'ASSIGN' | 'RECLAIM' | 'TRANSFER';
  reason: string;
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
  /** Each CAMS space keeps its own library; only an administrator may name a space other than their own. */
  scopeId?: string;
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
  /** The CAMS space the template belongs to; null when the template row is gone or unbound. */
  providerScopeId: string | null;
  providerScopeName: string | null;
  providerScopeExternalId: string | null;
  providerScopeType: 'ENTERPRISE_API' | 'EMPLOYEE_BUSINESS_APP' | null;
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

/** 助手一轮对话的终点，与服务端 `AssistantTurnResult.Kind` 一一对应。 */
export type AssistantTurnKind =
  | 'QUESTION'
  | 'CONFIRMATION_REQUIRED'
  | 'EXECUTED'
  | 'ANSWER'
  | 'ERROR';

/**
 * 历史条目的角色。只有这两种：服务端把未识别的角色一律当作**用户内容**，
 * 因此前端无法借历史把自己写成 `system` 去覆写提示词。
 */
export type AssistantHistoryRole = 'user' | 'assistant';

export interface AssistantHistoryTurn {
  role: AssistantHistoryRole;
  text: string;
}

/**
 * 确认卡片上「改前 → 改后」的一条。
 *
 * `before` 为 `null` 时表示**服务端拿不到「改前」的证据**（候选清单里没有这个字段）。
 * 那时渲染成「当前未知」而不是一片空白 —— 空白会被读成「没有变化」，与服务端的本意相反。
 * 服务端宁可留空也不猜：一个编出来的「改前」会被用户当成事实去核对。
 */
export interface AssistantProposalChange {
  /** 机器可读的字段名，用于列表 key 与将来的程序化处理。 */
  field: string;
  /** 直接展示的中文标签（如「时间」「内容」）。由服务端给，避免前后端各写一套。 */
  label: string;
  /** `null` / 缺席 = 当前值未知。 */
  before?: string | null;
  after: string;
}

/**
 * 确认卡片的载荷。
 *
 * `summary` 必须带**标题与日期** —— 自然语言匹配必然有歧义，真正的安全网不是让匹配更聪明，
 * 而是让「执行前的人眼复核」足够便宜；只显示「确认要标记完成吗？」用户无法判断认的是哪一条。
 *
 * `changes` 是 `summary` 的结构化补充：一句话读不出「它以为当前是什么」，
 * 而后者才是改动类动作里用户真正要核对的东西。空数组或缺席表示这个动作没有「改前」可言（如新建）。
 *
 * `arguments` 只用于只读展示：确认接口只接受 `pendingActionId`，
 * 前端**无法**通过改这里的参数把确认落到别处。
 */
export interface AssistantProposal {
  pendingActionId: string;
  tool: string;
  summary: string;
  changes?: AssistantProposalChange[];
  arguments?: Record<string, unknown>;
}

/**
 * `POST /api/assistant/messages` 的响应体。
 *
 * 服务端用 `@JsonInclude(NON_NULL)`，因此「不适用」的字段是**整体缺席**而不是 `null`，
 * 所以这里全部是可选的。渲染时必须按 `kind` 分支，而不是看某个字段在不在。
 */
export interface AssistantTurnResult {
  kind: AssistantTurnKind;
  message: string;
  missing?: string[];
  proposal?: AssistantProposal;
  /** 仅 `kind=ERROR` 时出现。与 HTTP 层的错误码不同族。 */
  errorCode?: string;
}

export interface AssistantMessageRequest {
  /**
   * 会话号。由**前端生成并持久化**，用于把同一段对话的消息与审计串起来。
   *
   * 它**不是凭证**：读写的归属一律按登录用户判定（服务端 SQL 带 `user_id`），
   * 拿别人的会话号来试只会读回空列表。正因如此前端可以自由生成，无须先申领。
   */
  conversationId?: string;
  history?: AssistantHistoryTurn[];
  text: string;
}

/**
 * `GET /api/assistant/conversations/{id}/messages` 的一条历史消息。
 *
 * 刻意**不含** `proposal` / `pendingActionId`：那张卡片能不能点取决于服务端待确认动作的
 * **当前状态**（可能已确认、已取消、已过期），而不是取决于历史上出现过它。
 * 靠历史恢复一张卡片，等于诱导用户去点一个大概率已经失效的按钮。
 */
export interface AssistantConversationMessage {
  role: AssistantHistoryRole;
  /** 仅助手行有值；用户行是 `null`（用户说的话没有「终点」这回事）。 */
  kind?: AssistantTurnKind | null;
  text: string;
  /** 服务端时间戳，仅用于展示与排障；顺序已由服务端保证，前端不依赖它排序。 */
  createdAt?: string;
}
