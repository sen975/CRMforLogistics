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

export interface MessageResponse {
  id: string;
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
  languageCode: string;
  body: string;
  placeholders: string[];
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
  redirectUri: string;
  state: string;
  expiresIn: number;
}

export interface WeComLoginResponse extends LoginResponse {
  viewerAuthToken: string | null;
  viewerExpiresIn: number;
}

export interface ApiError {
  code: string;
  message: string;
  traceId: string;
  fieldErrors: Record<string, string>;
}

export interface ChannelCapability {
  channelType: string;
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

export interface TemplateOperation {
  operationId: string;
  operationType: 'CREATE' | 'MODIFY' | 'SET_SEND_PERMISSION' | 'DELETE' | 'RECONCILE';
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
