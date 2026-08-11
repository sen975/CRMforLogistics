import assert from 'node:assert/strict';
import fs from 'node:fs';

const contractUrl = new URL('./message-center-v1.yaml', import.meta.url);
const contract = fs.readFileSync(contractUrl, 'utf8');

const requiredPaths = [
  '/api/v1/auth/login',
  '/api/v1/auth/logout',
  '/api/v1/auth/session',
  '/api/v1/auth/csrf',
  '/api/v1/contacts',
  '/api/v1/contacts/{contactId}',
  '/api/v1/contacts/{contactId}/merge',
  '/api/v1/contact-identities/{identityId}/split',
  '/api/v1/companies',
  '/api/v1/conversations/{conversationId}/messages',
  '/api/v1/conversations/{conversationId}/read',
  '/api/v1/email/messages',
  '/api/v1/email/attachments/{messageId}/{attachmentId}',
  '/api/v1/attachments/{attachmentId}/content',
  '/api/v1/contacts/{contactId}/call-records',
  '/api/v1/phone-contacts',
  '/api/v1/phone-repository',
  '/api/v1/contacts/{contactId}/timeline',
  '/api/v1/call-records/{callRecordId}',
  '/api/v1/call-records/{callRecordId}/audio-sessions',
  '/api/v1/call-records/{callRecordId}/audio',
  '/api/v1/call-records/{callRecordId}/retry',
  '/api/v1/call-records/{callRecordId}/transcript',
  '/api/v1/call-records/{callRecordId}/note',
  '/api/v1/channel-accounts',
  '/api/v1/sync/{channelType}',
  '/api/v1/events',
  '/api/v1/wecom/js-sdk-config',
  '/api/v1/wecom/authorization/callback',
  '/api/v1/wecom/login/attempts',
  '/api/v1/wecom/login/exchange',
  '/api/v1/wecom/conversation-view/sync',
  '/api/v1/wecom/conversation-view/sessions',
  '/api/v1/wecom/conversation-view/sessions/{viewerSessionId}',
  '/api/v1/wecom/conversation-view/events',
  '/api/v1/webhooks/{channelType}'
];

assert.match(contract, /^openapi: 3\.1\.0$/m);
for (const path of requiredPaths) {
  assert.ok(contract.includes(`  ${path}:`), `missing ${path}`);
}
assert.doesNotMatch(contract, /^  \/api\/(?!v1\/)/m, 'legacy unversioned route is forbidden');

const operations = extractOperations(contract);
assert.ok(operations.length >= 18, 'expected the complete v1 operation set');
const operationIds = new Set();
for (const operation of operations) {
  const label = `${operation.method.toUpperCase()} ${operation.path}`;
  const operationId = operation.body.match(/^      operationId: ([A-Za-z][A-Za-z0-9]*)$/m)?.[1];
  assert.ok(operationId, `${label} must define operationId`);
  assert.ok(!operationIds.has(operationId), `duplicate operationId ${operationId}`);
  operationIds.add(operationId);
  assert.match(operation.body, /^      description: .+$/m,
    `${label} must define a description`);
  assert.match(operation.body, /^      tags:\n        - [A-Za-z][A-Za-z0-9-]*$/m,
    `${label} must define tags`);
  assert.match(operation.body, /^      (parameters|requestBody):$/m,
    `${label} must define request schema input`);
  assert.match(operation.body, /^      responses:$/m, `${label} must define responses`);
  const successResponse = operation.body.match(
    /^        '2[0-9]{2}':([\s\S]*?)(?=^        (?:'[1-5](?:[0-9]{2}|XX)'|default):)/m)?.[1];
  assert.ok(successResponse, `${label} must define a successful response`);
  assert.match(successResponse, /schema:\n\s+(?:\$ref: '#\/components\/schemas\/|type: )/,
    `${label} must define a successful response schema`);
  assert.match(operation.body,
    /^        '4XX':\n          \$ref: '#\/components\/responses\/ApiErrorResponse'$/m,
    `${label} must define structured client errors`);
  assert.match(operation.body,
    /^        default:\n          \$ref: '#\/components\/responses\/ApiErrorResponse'$/m,
    `${label} must use ApiError for unexpected failures`);
}

assert.match(contract, /^security:\n  - sessionCookie: \[\]$/m,
  'session cookie must secure the API by default');
assert.match(contract,
  /^    sessionCookie:\n      type: apiKey\n      in: cookie\n      name: MESSAGE_CENTER_SESSION$/m);
assert.doesNotMatch(contract, /^\s+type: (http|oauth2|openIdConnect)$/m,
  'JWT and bearer-style authentication are forbidden');

const login = operation(operations, 'post', '/api/v1/auth/login');
assert.match(login.body, /^      security: \[\]$/m, 'login must be anonymous');
assert.match(login.body, /#\/components\/parameters\/TraceRequestId/,
  'login must accept the bounded trace request id');
assert.match(login.body, /#\/components\/parameters\/CsrfTokenHeader/,
  'login must require CSRF even though authentication is permitAll');
assert.match(login.body, /Set-Cookie/);
assert.match(login.body, /HttpOnly; Secure; SameSite=Strict/);

const csrf = operation(operations, 'get', '/api/v1/auth/csrf');
assert.match(csrf.body, /^      security: \[\]$/m, 'CSRF token bootstrap must be anonymous');
assert.match(csrf.body, /Set-Cookie/);
assert.match(csrf.body, /XSRF-TOKEN=csrf-token; Path=\/; Secure; SameSite=Strict/);
assert.doesNotMatch(csrf.body, /XSRF-TOKEN=[^\n]*HttpOnly/,
  'frontend must be able to read the CSRF cookie');
assert.match(contract, /^      name: X-XSRF-TOKEN$/m);
assert.match(contract, /^          const: X-XSRF-TOKEN$/m);
assert.match(contract, /^          const: XSRF-TOKEN$/m);
assert.doesNotMatch(contract, /X-CSRF-TOKEN/, 'legacy CSRF header name is forbidden');
assert.doesNotMatch(operation(operations, 'post', '/api/v1/auth/logout').body,
  /^      security: \[\]$/m, 'logout must require the authenticated session');

for (const path of ['/api/v1/contacts', '/api/v1/companies',
  '/api/v1/conversations/{conversationId}/messages']) {
  const listOperation = operation(operations, 'get', path);
  assert.match(listOperation.body, /#\/components\/parameters\/CursorParameter/,
    `${path} must use the bounded cursor parameter`);
  assert.match(listOperation.body, /#\/components\/parameters\/LimitParameter/,
    `${path} must use the bounded limit parameter`);
}
assert.match(contract, /^        maxLength: 2048$/m, 'cursor length must be bounded');
assert.match(contract, /^        maximum: 100$/m, 'page size must have an upper bound');
const messagePage = schema(contract, 'MessagePage');
assert.match(messagePage,
  /^      required: \[items, nextCursor, messageCount, threadRevision\]$/m,
  'message page must expose the thread freshness contract');
assert.match(messagePage,
  /^        messageCount:\n          type: integer\n          minimum: 0$/m,
  'message page must include a bounded message count from the same snapshot');
assert.match(messagePage,
  /^        threadRevision:\n          type: string\n          minLength: 1\n          maxLength: 128$/m,
  'message page must include an opaque thread revision');

const media = operation(operations, 'get', '/api/v1/attachments/{attachmentId}/content');
for (const contentType of [
  'image/jpeg', 'image/png', 'video/mp4', 'application/pdf', 'application/octet-stream'
]) {
  assert.ok(media.body.includes(`          ${contentType}:`),
    `attachment response missing bounded content type ${contentType}`);
}
assert.doesNotMatch(media.body, /^\s+(image|video|application)\/\*:/m,
  'wildcard attachment content types are forbidden');
assert.match(media.body, /x-max-response-bytes: [1-9][0-9]*/,
  'attachment response must document a byte upper bound');
assert.match(media.body, /limited to 20 MiB/);
assert.match(media.body, /x-max-response-bytes: 20971520/);
assert.match(media.body, /maximum: 20971520/);
assert.match(media.body, /maxLength: 20971520/);
assert.ok((contract.match(/20971520/g) ?? []).length >= 8,
  'all stream and attachment byte limits must use the Config 20 MiB default');
assert.doesNotMatch(contract, /26214400/, 'the stale 25 MiB media limit is forbidden');

const emailSend = operation(operations, 'post', '/api/v1/email/messages');
assert.match(emailSend.body, /multipart\/form-data:/,
  'email sending must use streaming multipart form data');
assert.match(emailSend.body, /#\/components\/schemas\/EmailSendMultipartRequest/);
assert.match(emailSend.body, /EMAIL_MULTIPART_REQUIRED|EMAIL_MULTIPART_FIELD_INVALID/);
assert.match(emailSend.body, /EMAIL_SENT_HISTORY_FAILED/);
assert.match(emailSend.body, /EMAIL_SEND_OUTCOME_UNKNOWN/);
const emailRequest = schema(contract, 'EmailSendMultipartRequest');
assert.match(emailRequest, /required: \[to, subject\]/);
assert.match(emailRequest, /file:/);
assert.match(emailRequest, /maxItems: 16/);
assert.match(emailRequest, /maxLength: 20971520/);
assert.match(emailRequest, /format: binary/);

const emailDownload = operation(operations, 'get', '/api/v1/email/attachments/{messageId}/{attachmentId}');
assert.match(emailDownload.body, /X-WeCom-Viewer-Auth/);
assert.match(emailDownload.body, /Content-Disposition:/);
assert.match(emailDownload.body, /attachment; filename\*=UTF-8''/);
assert.match(emailDownload.body, /Content-Length:/);
assert.match(emailDownload.body, /Cache-Control:/);
assert.match(emailDownload.body, /private, no-store/);
assert.match(emailDownload.body, /X-Content-Type-Options:/);
assert.match(emailDownload.body, /nosniff/);
assert.match(emailDownload.body, /EMAIL_ATTACHMENT_VIEWER_REQUIRED|EMAIL_ATTACHMENT_NOT_FOUND/);
const emailProjection = schema(contract, 'EmailAttachmentProjection');
assert.match(emailProjection, /required: \[id, fileName, mimeType, sizeBytes, state, errorCode\]/);
assert.match(emailProjection, /enum: \[stored, rejected\]/);
assert.match(emailProjection, /EMAIL_ATTACHMENT_(COUNT_LIMIT|SIZE_LIMIT|STORAGE_FULL|READ_FAILED|STORE_FAILED)/);
assert.doesNotMatch(emailProjection, /relativePath/);
assert.doesNotMatch(emailDownload.body, /Content-Disposition:[\s\S]{0,300}inline;/i,
  'email attachments must not be served with inline disposition');

assert.match(contract, /^    ApiError:$/m);
assert.match(contract, /^      required: \[code, message, traceId, fieldErrors\]$/m);
assert.match(contract, /^    SseEvent:$/m);
assert.match(contract,
  /^      required: \[type, version, resourceId, sequence, occurredAt, payload\]$/m);
assert.match(contract, /text\/event-stream:/);
assert.match(contract, /x-sse-event-schema: '#\/components\/schemas\/SseEvent'/);
const eventStream = operation(operations, 'get', '/api/v1/events');
assert.match(eventStream.body,
  /text\/event-stream:\n              schema:\n                type: string/,
  'SSE wire framing must remain a string response');
assert.match(eventStream.body, /shared SSE adapter MUST validate each frame against SseEvent/,
  'SSE adapter validation responsibility must be explicit');
const sseSchema = schema(contract, 'SseEvent');
assert.match(sseSchema, /^        payload:\n          type: object\n          maxProperties: 20$/m);
assert.match(sseSchema,
  /^          propertyNames:\n            type: string\n            maxLength: 100\n            pattern: '\^\[A-Za-z\]\[A-Za-z0-9_.-\]\{0,99\}\$'$/m,
  'SSE payload property names must be bounded identifiers');
assert.match(sseSchema,
  /^          additionalProperties:\n            oneOf:\n              - type: string\n                maxLength: 2048\n              - type: integer\n                format: int64\n                minimum: -9007199254740991\n                maximum: 9007199254740991\n              - type: number\n                format: double\n                minimum: -1000000000000\n                maximum: 1000000000000\n                not:\n                  type: integer\n              - type: boolean\n              - type: 'null'$/m,
  'SSE payload values must be bounded non-nested scalars');
assert.doesNotMatch(sseSchema, /additionalProperties: true/,
  'SSE payload must not allow unbounded nested values');

const webhook = operation(operations, 'post', '/api/v1/webhooks/{channelType}');
assert.match(webhook.body, /^      security:\n        - webhookSignature: \[\]\n        - weComSignature: \[\]$/m,
  'webhook header and WeCom query signatures must be alternative schemes');
for (const parameter of [
  'WebhookContentLength', 'WebhookTimestampHeader', 'WebhookNonceHeader',
  'WeComTimestampQuery', 'WeComNonceQuery'
]) {
  assert.match(webhook.body, new RegExp(`#/components/parameters/${parameter}`),
    `webhook missing ${parameter}`);
}
assert.match(webhook.body, /x-max-body-bytes: 1048576/);
assert.match(webhook.body,
  /application\/json:\n            schema:\n              \$ref: '#\/components\/schemas\/RawWebhookBody'/);
assert.match(webhook.body,
  /application\/xml:\n            schema:\n              \$ref: '#\/components\/schemas\/RawWebhookBody'/);
assert.doesNotMatch(webhook.body, /additionalProperties: true/,
  'webhook operation must not accept arbitrary deserialized JSON');
for (const requirement of [
  /based on channelType/i,
  /exact raw body bytes/i,
  /older than five minutes/i,
  /previously used nonce/i,
  /server enforces these conditional parameters/i
]) assert.match(webhook.body, requirement);

assert.match(contract,
  /^    weComSignature:\n      type: apiKey\n      in: query\n      name: msg_signature$/m);
assert.match(contract,
  /^    WebhookContentLength:\n      name: Content-Length\n      in: header\n      required: true\n      schema:\n        type: integer\n        format: int64\n        minimum: 0\n        maximum: 1048576$/m);
const rawWebhookBody = schema(contract, 'RawWebhookBody');
assert.match(rawWebhookBody,
  /^    RawWebhookBody:\n      type: string\n      format: binary\n      maxLength: 1048576$/m);
assert.doesNotMatch(rawWebhookBody, /additionalProperties: true/);
assert.doesNotMatch(contract, /^    ChannelWebhookRequest:$/m,
  'deserialized arbitrary webhook schema is forbidden');

const wecomConfig = operation(operations, 'get', '/api/v1/wecom/js-sdk-config');
assert.match(wecomConfig.body, /operationId: getWeComJsSdkConfig/);
assert.match(wecomConfig.body, /WECOM_ALLOWED_JSAPI_ORIGINS/);
assert.match(wecomConfig.body, /wwapp\.invokeJsApiByCallInfo/);
assert.match(wecomConfig.body, /^        - name: X-WeCom-Viewer-Auth$/m);

const wecomAuthorizationCallback = operation(operations, 'post',
  '/api/v1/wecom/authorization/callback');
assert.match(wecomAuthorizationCallback.body, /operationId: receiveWeComAuthorizationCallback/);
assert.match(wecomAuthorizationCallback.body, /^      security: \[\]$/m);
for (const parameter of ['msg_signature', 'timestamp', 'nonce']) {
  assert.match(wecomAuthorizationCallback.body, new RegExp(`^        - name: ${parameter}$`, 'm'));
}
assert.match(wecomAuthorizationCallback.body, /application\/xml:/);
assert.match(wecomAuthorizationCallback.body, /text\/plain:/);
const wecomAuthorizationCallbackBody = schema(contract, 'WeComAuthorizationCallbackBody');
assert.match(wecomAuthorizationCallbackBody, /maxLength: 1048576/);
assert.doesNotMatch(wecomAuthorizationCallbackBody,
  /permanent_code|suite_ticket|access_token|secret/i);
assert.doesNotMatch(
  wecomAuthorizationCallback.body.match(/^      responses:([\s\S]*)$/m)?.[1] ?? '',
  /permanent_code|suite_ticket|access_token|secret/i,
  'callback responses must not expose credential material');
const wecomAuthorizationCallbackVerification = operation(operations, 'get',
  '/api/v1/wecom/authorization/callback');
assert.match(wecomAuthorizationCallbackVerification.body,
  /operationId: verifyWeComAuthorizationCallback/);
assert.match(wecomAuthorizationCallbackVerification.body, /^      security: \[\]$/m);
for (const parameter of ['msg_signature', 'timestamp', 'nonce', 'echostr']) {
  assert.match(wecomAuthorizationCallbackVerification.body,
    new RegExp(`^        - name: ${parameter}$`, 'm'));
}
assert.match(wecomAuthorizationCallbackVerification.body, /text\/plain:/);

const wecomAttempt = operation(operations, 'post', '/api/v1/wecom/login/attempts');
assert.match(wecomAttempt.body, /operationId: createWeComLoginAttempt/);
assert.match(wecomAttempt.body, /^      security: \[\]$/m);
assert.match(schema(contract, 'WeComLoginAttemptResponse'),
  /required: \[loginType, appId, redirectUri, state, expiresIn\]/);
assert.match(schema(contract, 'WeComLoginAttemptResponse'), /const: ServiceApp/);
assert.doesNotMatch(schema(contract, 'WeComLoginAttemptResponse'), /agentId:/);
assert.doesNotMatch(schema(contract, 'WeComLoginAttemptResponse'),
  /secret|access_token|ticket|signature|viewerAuthToken/i);

const wecomExchange = operation(operations, 'post', '/api/v1/wecom/login/exchange');
assert.match(wecomExchange.body, /^      security: \[\]$/m);
assert.match(schema(contract, 'WeComLoginExchangeRequest'), /required: \[code, state\]/);
assert.match(schema(contract, 'WeComLoginExchangeRequest'), /^        state:$/m);

const wecomSync = operation(operations, 'post', '/api/v1/wecom/conversation-view/sync');
assert.match(wecomSync.body, /operationId: syncWeComConversationView/);
assert.match(wecomSync.body, /^      security: \[\]$/m);
assert.match(wecomSync.body, /^        - name: X-WeCom-Viewer-Auth$/m);
assert.match(wecomSync.body, /without requiring an existing contact point/);
assert.match(wecomSync.body, /WeComConversationSyncResult/);
assert.match(schema(contract, 'WeComConversationSyncResult'),
  /required: \[pages, stored, skipped\]/);

const wecomSession = operation(operations, 'post', '/api/v1/wecom/conversation-view/sessions');
assert.match(wecomSession.body, /operationId: createWeComConversationViewerSession/);
assert.match(wecomSession.body, /read permission/);
assert.match(wecomSession.body, /Synchronize bounded WeCom chatdata indexes/);
for (const status of ['409', '429', '500', '502', '503', '504']) {
  assert.match(wecomSession.body, new RegExp(`^        '${status}':$`, 'm'));
}
const weComViewerCreate = schema(contract, 'WeComViewerSessionCreateRequest');
assert.match(weComViewerCreate, /required: \[contactPointId, viewerAuthToken, messageIds\]/);
assert.match(weComViewerCreate, /^        viewerAuthToken:$/m);
assert.match(weComViewerCreate,
  /^        messageIds:\n          type: array\n          minItems: 1\n          maxItems: 15\n          uniqueItems: true\n          items:\n            type: string\n            minLength: 1\n            maxLength: 256$/m);
assert.doesNotMatch(weComViewerCreate, /^        wecomUserId:$/m);
const weComViewerDetail = schema(contract, 'WeComViewerSessionDetail');
assert.match(weComViewerDetail,
  /^        messages:\n          type: array\n          maxItems: 15$/m);
const wecomSessionDetail = operation(operations, 'get', '/api/v1/wecom/conversation-view/sessions/{viewerSessionId}');
assert.match(wecomSessionDetail.body, /^        - name: X-WeCom-Viewer-Auth$/m);
assert.match(wecomSessionDetail.body, /^          in: header$/m);
assert.doesNotMatch(wecomSessionDetail.body, /^        - name: viewerAuthToken$/m);
assert.match(contract, /^    WeComViewerMessage:$/m);
assert.match(schema(contract, 'WeComViewerMessage'), /required: \[msgid, secretKey\]/);
assert.doesNotMatch(schema(contract, 'WeComViewerMessage'), /access_token|corpsecret|jsapi_ticket/i);
const wecomViewerEvent = operation(operations, 'post', '/api/v1/wecom/conversation-view/events');
assert.match(wecomViewerEvent.body, /^        - name: X-WeCom-Viewer-Auth$/m);
assert.match(schema(contract, 'WeComViewerEventRequest'), /const: component_error/);
assert.doesNotMatch(schema(contract, 'WeComViewerEventRequest'), /viewerAuthToken|secretKey/);
for (const [method, path, operationId] of [
  ['post', '/api/v1/contacts/{contactId}/call-records', 'createCallRecord'],
  ['post', '/api/v1/phone-contacts', 'createPhoneContact'],
  ['get', '/api/v1/phone-repository', 'listPhoneRepository'],
  ['get', '/api/v1/contacts/{contactId}/timeline', 'getContactTimeline'],
  ['get', '/api/v1/call-records/{callRecordId}', 'getCallRecord'],
  ['post', '/api/v1/call-records/{callRecordId}/audio-sessions', 'createCallAudioSession'],
  ['get', '/api/v1/call-records/{callRecordId}/audio', 'streamCallAudio'],
  ['post', '/api/v1/call-records/{callRecordId}/retry', 'retryCallRecordTranscription'],
  ['patch', '/api/v1/call-records/{callRecordId}/transcript', 'reviseCallRecordTranscript'],
  ['patch', '/api/v1/call-records/{callRecordId}/note', 'reviseCallRecordNote']
]) {
  const callOperation = operation(operations, method, path);
  assert.match(callOperation.body, new RegExp(`operationId: ${operationId}`));
  assert.match(callOperation.body, /X-WeCom-Viewer-Auth|CallRecordId|CallRecordContactId/);
}
assert.match(contract, /^    CallRecordDetail:$/m);
assert.match(contract, /^    CreateCallRecordRequest:$/m);
assert.match(schema(contract, 'CreateCallRecordRequest'), /required: \[phonePointId, direction, occurredAt, clientRequestId, file\]/);
assert.match(schema(contract, 'CallRecordDetail'), /note/);
assert.match(schema(contract, 'ReviseCallRecordNoteRequest'), /required: \[note, expectedVersion\]/);
assert.match(schema(contract, 'PhoneRepositoryPage'), /required: \[items, nextCursor, totalCount\]/);
assert.match(contract, /audio\/mpeg/);
assert.match(contract, /104857600/);
assert.equal(operations.length, 40);

console.log(`validated ${operations.length} OpenAPI operations`);

function extractOperations(source) {
  const lines = source.split(/\r?\n/);
  const result = [];
  let currentPath;
  for (let index = 0; index < lines.length; index += 1) {
    const pathMatch = lines[index].match(/^  (\/api\/v1\/[^:]*):$/);
    if (pathMatch) {
      currentPath = pathMatch[1];
      continue;
    }
    const methodMatch = lines[index].match(/^    (get|post|put|patch|delete):$/);
    if (!methodMatch || !currentPath) continue;
    let end = index + 1;
    while (end < lines.length
      && !/^  \/api\/v1\/[^:]*:$/.test(lines[end])
      && !/^    (get|post|put|patch|delete):$/.test(lines[end])
      && lines[end] !== 'components:') {
      end += 1;
    }
    result.push({
      path: currentPath,
      method: methodMatch[1],
      body: lines.slice(index, end).join('\n')
    });
  }
  return result;
}

function operation(all, method, path) {
  const found = all.find(candidate => candidate.method === method && candidate.path === path);
  assert.ok(found, `missing ${method.toUpperCase()} ${path}`);
  return found;
}

function schema(source, name) {
  const lines = source.split(/\r?\n/);
  const start = lines.findIndex(line => line === `    ${name}:`);
  assert.notEqual(start, -1, `missing schema ${name}`);
  let end = start + 1;
  while (end < lines.length && !/^    [A-Za-z][A-Za-z0-9]*:$/.test(lines[end])) end += 1;
  return lines.slice(start, end).join('\n');
}
