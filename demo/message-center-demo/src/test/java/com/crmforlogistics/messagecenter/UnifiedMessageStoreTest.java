package com.crmforlogistics.messagecenter;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.aliyun.sdk.service.cams20200606.models.SendChatappMessageRequest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class UnifiedMessageStoreTest {
    public static void main(String[] args) throws Exception {
        mergesEmailAndChatAppContactPointsIntoOneTimeline();
        storesContactGroupProfileWithoutLosingMergedPoints();
        splitsMergedContactPointWithoutDeletingRawMessages();
        splitsLegacyEmailContactGroupWhenOnlyOnePointRemains();
        threadPageReturnsRecentTenMessagesAndCursor();
        threadPageUsesDeletedCursorAsTimelineBoundary();
        threadPageRevisionChangesWhenMessagesChangeWithoutCountChange();
        databaseThreadPageUsesLimitPlusOneAndUuidCursor();
        threadPageRejectsNonEmptyCursorMissingRequiredFields();
        frontendThreadPageCacheIsBounded();
        eventHubRemovesDeadClientsWithoutWaitingForBusinessMessages();
        eventHubSerializesWritesPerSseClient();
        eventHubPublishesTemplateChanges();
        templateRouteServesLastSuccessfulSnapshot();
        wecomViewerSignatureUsesOfficialJsapiAlgorithmAndOriginAllowlist();
        wecomViewerSessionsAreBoundedUserScopedAndReadMessageReferences();
        wecomViewerLoginExchangeDoesNotPublishTokenWhenAuditFails();
        wecomViewerRoutesReturnBoundedConfigAndSessionPayloads();
        wecomSimulationRoutesAreNotExposedAtRuntime();
        exposesWecomAdapterAsAvailableViewerChannel();
        chatAppStatusRecordsUpdateOriginalMessageInsteadOfCreatingMessages();
        chatAppMediaJsonMessagesRenderCaptionAndAttachment();
        chatAppMediaPlaceholderTextShowsCaptionWithoutImagePrefix();
        chatAppMediaMessagesExposeProxyMetadata();
        chatAppMediaMessagesExtractUrlFromNestedRawMessageJson();
        mediaGatewayBuildsFreshOssSignedUrlFromStoredObjectKey();
        mediaGatewayPrefersCamsPresignedUrlBeforeOssUrl();
        mediaGatewayNormalizesProtocolRelativePresignedUrl();
        mediaGatewaySkipsSlowCamsPresignAndFallsBackToFreshSignedOssUrl();
        mediaGatewayTriesFreshSignedOssUrlBeforeExpiredMediaUrlWhenCamsFails();
        mediaGatewaySummarizesOssXmlErrorCode();
        mediaGatewayReportsCamsAndFallbackDownloadFailures();
        mediaGatewayCachesDownloadedMediaLocally();
        rendersChatAppTemplateMessagesFromTemplateCache();
        chatAppTemplateMessagesUseTemplateRequestType();
        chatAppTemplateRequestsOmitMessageType();
        frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions();
        new UnifiedMessageStoreTest().frontendWeComSegmentsAreStableAndBounded();
        new UnifiedMessageStoreTest().frontendWeComInlineExpansionIsBoundedAndIndependent();
        rendersWebShellWithChineseCopyAndUnifiedSendActions();
        rendersWebShellWithPagedThreadRequestContract();
        apiThreadsRouteReturnsPagedObjectAndParsesCursorLimit();
        rendersWebShellWithOlderThreadScrollLoader();
        frontendThreadPaginationBehaviorLoadsOlderPages();
        frontendWeComColdContactSwitchKeepsOldPanelUntilReady();
        emailMessagesKeepBodyTextOutOfTimelineBubble();
        emailSendShowsValidationAndFailureFeedback();
        mailSenderUsesSenderDomainForMessageId();
        emailSync139DirectModeConfiguresImapsTlsProfile();
        emailSync139DirectModeInstallsDedicatedSocketFactory();
        emailSync139UsesOpenSslImapFallback();
        openSslImapClientExtractsRfc822Literals();
        chatAppTextMessagesUseMessageBodyAsContactPreview();
        emailInboxWriterStoresImapMessagesInLegacyInboxJsonlFormat();
        chatAppHistoryStoreDeduplicatesAndFeedsUnifiedTimeline();
        chatAppWebhookDelegatesWritesToHistoryStore();
        chatAppStatusWebhookPreservesLegacyProjection();
        chatAppSyncRouteUsesSynchronizerAndPreservesLockBusyResult();
        chatAppHistoryStoreRefreshesExpiredMediaUrlForExistingMessage();
        chatAppHistorySyncDefaultsToShortFirstRunWindow();
        chatAppHistorySyncUsesLatestLocalTimestampForIncrementalWindow();
        chatAppHistorySyncQueuesMediaPrecacheByDefaultWithoutBlockingMessageSync();
        chatAppHistorySyncPreCachesMediaWithoutBlockingMessageSync();
        chatAppHistorySyncDoesNotRetryUnchangedMediaByDefault();
        chatAppHistorySyncExtractsCamsUrlFieldForMediaCache();
        projectsPhoneIdentityAcrossMergeAndSplit();
        appWiresCallRuntimeBeforeLegacyRoutesAndClosesItBeforeServer();
        phoneCallUiUsesUnifiedTimelineAndAuthenticatedBoundedPlayback();
		frontendPhoneCallBehaviorUsesCanonicalIdentityAndBoundedAudioRecovery();
    }

    @Test
    void callRuntimeWiringStaysThinAndOrdered() throws Exception {
        appWiresCallRuntimeBeforeLegacyRoutesAndClosesItBeforeServer();
    }

    @Test
    void phoneCallUiContractIsPresent() {
        phoneCallUiUsesUnifiedTimelineAndAuthenticatedBoundedPlayback();
    }

	@Test
	void phoneCallUiBehaviorIsBoundedAndAuthenticated() throws Exception {
		frontendPhoneCallBehaviorUsesCanonicalIdentityAndBoundedAudioRecovery();
	}

    @Test
    void weComViewerMessagesRenderInsideUnifiedTimeline() {
        frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions();
    }

    @Test
    void frontendWeComSegmentsAreStableAndBounded() throws Exception {
        frontendWeComSegmentContractIsPresent();
        Path dir = Files.createTempDirectory("message-center-wecom-segmentation-test");
        Path html = dir.resolve("page.html");
        Files.writeString(html, App.pageHtml(), StandardCharsets.UTF_8);
        Path probe = Path.of(getClass().getResource("/wecom-segmentation-probe.mjs").toURI());
        Process process = new ProcessBuilder("node", probe.toString(), html.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("wecom segmentation probe timed out");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("wecom segmentation probe failed:\n" + output);
        }
    }

    @Test
    void frontendWeComSegmentFramesUseOfficialTemplateAndReuseStableHosts() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-segment-frame-test");
        Path html = dir.resolve("page.html");
        Files.writeString(html, App.pageHtml(), StandardCharsets.UTF_8);
        Path probe = Path.of(getClass().getResource("/wecom-segment-frame-probe.mjs").toURI());
        Process process = new ProcessBuilder("node", probe.toString(), html.toString())
                .redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("wecom segment frame probe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new AssertionError("wecom segment frame probe failed:\n" + output);
        }
    }

    @Test
    void frontendWeComSegmentLifecycleIsBoundedAndRetriesAtomically() {
        String html = App.pageHtml();

        assertContains(html, "const WECOM_ACTIVE_CONTACT_SEGMENT_LIMIT = 15;");
        assertContains(html, "function setWeComSegmentStatus(host, message, failed = false, reveal = true)");
        assertContains(html, "function trimWeComSegmentFrames(contactPointId, protectedIds = [])");
        assertContains(html, "async function retryWeComSegment(segmentId)");
        assertContains(html, "const stagingHost = document.createElement('div');");
        assertContains(html, "stagingHost.className = 'wecom-contact-window';");
        assertContains(html, "if (result?.status === 'mounted')");
        assertContains(html, "entry.host = host;");
        assertContains(html, ".wecom-segment-status { display:flex; align-items:center; gap:6px; height:32px;");
    }

    @Test
    void frontendWeComInlineExpansionIsBoundedAndIndependent() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-inline-expansion-test");
        Path html = dir.resolve("page.html");
        Files.writeString(html, App.pageHtml(), StandardCharsets.UTF_8);
        Path probe = Path.of(getClass().getResource("/wecom-inline-expansion-probe.mjs").toURI());
        Process process = new ProcessBuilder("node", probe.toString(), html.toString())
                .redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("wecom inline expansion probe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new AssertionError("wecom inline expansion probe failed:\n" + output);
        }
    }

    @Test
    void weComTimelineUsesPersistedDirectionAndExternalContactPoint() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-direction-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp.jsonl");
        Path wecomData = dir.resolve("wecom.jsonl");
        Files.createDirectories(emailData);
        Files.writeString(wecomData,
                "{\"msgid\":\"wecom-out\",\"secret_key\":\"s-out\",\"external_userid\":\"external-1\",\"userid\":\"employee-1\",\"send_time\":1,\"msgtype\":\"1\",\"direction\":\"outbound\"}\n"
                        + "{\"msgid\":\"wecom-in\",\"secret_key\":\"s-in\",\"external_userid\":\"external-1\",\"userid\":\"employee-1\",\"send_time\":2,\"msgtype\":\"1\",\"direction\":\"inbound\"}\n",
                StandardCharsets.UTF_8);
        UnifiedMessageStore store = new UnifiedMessageStore(testConfig(dir, emailData, chatData,
                Map.of("WECOM_DATA_FILE", wecomData.toString())));

        List<UnifiedMessage> thread = store.thread("wecom:external-1");

        assertEquals(2, thread.size());
        assertEquals("outbound", thread.get(0).direction);
        assertEquals("inbound", thread.get(1).direction);
    }

    @Test
    void weComTimelineKeepsLegacyDirectionFallback() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-legacy-direction-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp.jsonl");
        Path wecomData = dir.resolve("wecom.jsonl");
        Files.createDirectories(emailData);
        Files.writeString(wecomData,
                "{\"msgid\":\"legacy-default\",\"secret_key\":\"s-default\",\"external_userid\":\"external-1\",\"userid\":\"employee-1\",\"send_time\":1,\"msgtype\":\"1\"}\n"
                        + "{\"msgid\":\"legacy-in\",\"secret_key\":\"s-in\",\"external_userid\":\"external-1\",\"userid\":\"employee-1\",\"send_time\":2,\"msgtype\":\"1\",\"origin\":3}\n"
                        + "{\"msgid\":\"legacy-out\",\"secret_key\":\"s-out\",\"external_userid\":\"external-1\",\"userid\":\"employee-1\",\"send_time\":3,\"msgtype\":\"1\",\"origin\":1}\n",
                StandardCharsets.UTF_8);
        UnifiedMessageStore store = new UnifiedMessageStore(testConfig(dir, emailData, chatData,
                Map.of("WECOM_DATA_FILE", wecomData.toString())));

        List<UnifiedMessage> thread = store.thread("wecom:external-1");

        assertEquals(3, thread.size());
        assertEquals("inbound", thread.get(0).direction);
        assertEquals("inbound", thread.get(1).direction);
        assertEquals("outbound", thread.get(2).direction);
    }

    private static void phoneCallUiUsesUnifiedTimelineAndAuthenticatedBoundedPlayback() {
        String html = App.pageHtml();
        assertContains(html, "data-channel=\"callRecord\"");
        assertContains(html, "accept=\"audio/mpeg,.mp3\"");
        assertContains(html, "function uploadCallRecord()");
        assertContains(html, "function renderCallRecordCard(item)");
        assertContains(html, "function openCallRecordDetail(callRecordId)");
		assertContains(html, "function renewCallAudioSession(callRecordId, generation");
		assertContains(html, "function refreshCallRecordDetail(callRecordId, generation");
		assertContains(html, "CALL_AUDIO_RENEW_MS = 240000");
		assertContains(html, "CALL_DETAIL_POLL_MAX_FAILURES = 5");
		assertContains(html, "const viewerApi = async (url, options = {}) =>");
		assertContains(html, "async function handleViewerAuthFailure(error)");
		assertContains(html, "async function refreshInBackground(silent)");
		assertContains(html, "setInterval(() => refreshInBackground(true), 5000)");
		assertContains(html, "state.eventSource.onmessage = () => refreshInBackground(true);");
		assertContains(html, "new XMLHttpRequest()");
		assertContains(html, "X-WeCom-Viewer-Auth");
		assertContains(html, "'/api/v1/contacts/' + encodeURIComponent(id) + '/timeline?limit='");
		int loginReturn = html.indexOf("async function returnToWeComLogin(message)");
		int callCleanup = html.indexOf("clearCallDetailActivity();", loginReturn);
		int enterMessageCenter = html.indexOf("async function enterMessageCenter()", loginReturn);
		assertTrue(loginReturn >= 0 && callCleanup > loginReturn && callCleanup < enterMessageCenter,
				"returning to WeCom login must release call detail timers");
        assertNotContains(html, "viewerAuthToken=");
    }

	private static void frontendPhoneCallBehaviorUsesCanonicalIdentityAndBoundedAudioRecovery()
			throws Exception {
		Path dir = Files.createTempDirectory("message-center-phone-ui-test");
		Path html = dir.resolve("page.html");
		Path probe = dir.resolve("probe.mjs");
		Files.writeString(html, App.pageHtml(), StandardCharsets.UTF_8);
		Files.writeString(probe, frontendPhoneCallProbe(), StandardCharsets.UTF_8);

		Process process = new ProcessBuilder("node", probe.toString(), html.toString())
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(10, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new AssertionError("frontend phone call probe timed out");
		}
		if (process.exitValue() != 0) {
			throw new AssertionError("frontend phone call probe failed:\n" + output);
		}
	}

	private static String frontendPhoneCallProbe() {
		return """
				import assert from 'node:assert/strict';
				import fs from 'node:fs';
				import vm from 'node:vm';

				const html = fs.readFileSync(process.argv[2], 'utf8');
				let script = html.match(/<script>([\\s\\S]*?)<\\/script>/)?.[1];
				assert.ok(script, 'page must contain the embedded script');
				script = script.replace(/\\n\\s*initWeComLogin\\(\\)\\.catch\\(showWeComLoginError\\);\\s*$/, '');

				const elements = new Map();
				const requests = [];
				const xhrs = [];
				const clearedTimeouts = [];
				const clearedIntervals = [];
				const timeoutDelays = [];
				const control = { failAudioSession:false, deferAudioSession:false, failDetail:false, deferMutation:false, detailState:'queued' };
				let timerId = 0;

				function classList() {
				  const values = new Set();
				  return {
					add: value => values.add(value),
					remove: value => values.delete(value),
					contains: value => values.has(value),
					toggle: (value, force) => force === undefined ? (values.has(value) ? values.delete(value) : values.add(value)) : (force ? values.add(value) : values.delete(value))
				  };
				}

				function element(id) {
				  if (elements.has(id)) return elements.get(id);
				  const attributes = new Map();
				  const listeners = new Map();
				  const el = {
					id,
					dataset: {},
					style: { display:'', setProperty() {} },
					classList: classList(),
					hidden: false,
					disabled: false,
					textContent: '',
					value: '',
					files: [],
					clientHeight: id === 'thread' ? 120 : 0,
					scrollHeight: id === 'thread' ? 240 : 0,
					scrollTop: 0,
					currentTime: 0,
					duration: 12,
					paused: true,
					querySelectorAll: () => [],
					closest: () => null,
					appendChild() {},
					remove() {},
					click() {},
					focus() {},
					setAttribute(name, value) { attributes.set(name, String(value)); },
					getAttribute(name) { return attributes.has(name) ? attributes.get(name) : null; },
					removeAttribute(name) { attributes.delete(name); },
					addEventListener(name, callback) { listeners.set(name, callback); },
					load() { const callback = listeners.get('loadedmetadata'); if (callback) { listeners.delete('loadedmetadata'); callback(); } },
					play() { this.paused = false; return Promise.resolve(); },
					pause() { this.paused = true; },
					replaceWith(replacement) { elements.set(id, replacement); }
				  };
				  Object.defineProperty(el, 'innerHTML', {
					get() { return this._innerHTML || ''; },
					set(value) {
					  this._innerHTML = String(value || '');
					  if (id === 'detail') ['callAudio','callAudioError','callDetailUpdateError','callRevisionText','retryCallRecord','reviseCallRecord'].forEach(childId => elements.delete(childId));
					  if (id === 'sendPanel') ['callFile','callDirection','callOccurredAt','callPhonePoint','callUploadProgress','callUploadStatus','uploadCallRecordButton'].forEach(childId => elements.delete(childId));
					}
				  });
				  elements.set(id, el);
				  return el;
				}

				const document = {
				  activeElement: null,
				  visibilityState: 'visible',
				  body: { appendChild() {} },
				  head: { appendChild() {} },
				  addEventListener() {},
				  createElement: tag => element(`created-${tag}-${elements.size}`),
				  getElementById: id => element(id),
				  querySelectorAll: () => []
				};

				class TestFormData {
				  constructor() { this.entries = []; }
				  append(name, value) { this.entries.push([name, value]); }
				}

				class TestXhr {
				  constructor() { this.upload = {}; this.headers = {}; this.status = 0; this.responseText = ''; xhrs.push(this); }
				  open(method, url) { this.method = method; this.url = url; }
				  setRequestHeader(name, value) { this.headers[name] = value; }
				  send(form) { this.form = form; }
				}

				const callDetail = {
				  id:'call-1', contactAnchorPointId:'contact-1', phonePointId:'phone:8613800000000', direction:'inbound',
				  occurredAt:'2026-08-01T01:00:00Z', createdAt:'2026-08-01T01:01:00Z', version:1,
				  audio:{ originalFileName:'call.mp3', sizeBytes:123, durationSeconds:8 },
				  transcription:{ state:'queued', result:{ originalText:'', segments:[] }, error:{} }, revisions:[], currentRevisionId:''
				};
				const callItem = { type:'callRecord', occurredAt:callDetail.occurredAt, sortId:'call:call-1', payload:{ id:'call-1', phonePointId:callDetail.phonePointId, direction:'inbound', occurredAt:callDetail.occurredAt, durationSeconds:8, state:'queued', version:1 } };

				function response(url, options = {}) {
				  requests.push({ url:String(url), options });
				  if (String(url).endsWith('/audio-sessions')) {
					if (control.failAudioSession) {
					  const result = { ok:false, status:503, statusText:'Unavailable', text:async () => JSON.stringify({ code:'AUDIO_SESSION_UNAVAILABLE', message:'播放授权不可用' }), json:async () => ({}) };
					  if (control.deferAudioSession) return new Promise(resolve => { control.resolveAudioSession = () => resolve(result); });
					  return result;
					}
					return { ok:true, status:204, statusText:'', text:async () => '', json:async () => ({}) };
				  }
				  if (String(url).endsWith('/retry') || String(url).endsWith('/transcript')) {
					const updated = { ...callDetail, version:2 };
					const result = { ok:true, status:200, statusText:'', text:async () => JSON.stringify(updated), json:async () => updated };
					if (control.deferMutation) return new Promise(resolve => { control.resolveMutation = () => resolve(result); });
					return result;
				  }
				  if (String(url).includes('/api/v1/phone-repository')) {
					const page = { items:[{ id:'call-1', contactDisplayName:'Buyer', phonePointId:'phone:8613800000000', note:'回电', transcriptionState:'queued' }], nextCursor:'', totalCount:1 };
					return { ok:true, status:200, statusText:'', text:async () => JSON.stringify(page), json:async () => page };
				  }
				  if (String(url).endsWith('/api/v1/phone-contacts')) {
					const binding = { contactId:'contact-new', phonePointId:'phone:1390000000000', displayName:'新联系人' };
					return { ok:true, status:200, statusText:'', text:async () => JSON.stringify(binding), json:async () => binding };
				  }
				  if (String(url).includes('/api/v1/call-records/call-1')) {
					if (control.failDetail) return { ok:false, status:503, statusText:'Unavailable', text:async () => JSON.stringify({ code:'CALL_DETAIL_UNAVAILABLE', message:'详情暂不可用' }), json:async () => ({}) };
					const detail = { ...callDetail, transcription:{ ...callDetail.transcription, state:control.detailState } };
					return { ok:true, status:200, statusText:'', text:async () => JSON.stringify(detail), json:async () => detail };
				  }
				  if (String(url).includes('/timeline?limit=')) {
					const page = { items:[callItem], itemCount:1, nextCursor:'', threadRevision:'rev-1' };
					return { ok:true, status:200, statusText:'', text:async () => JSON.stringify(page), json:async () => page };
				  }
				  throw new Error(`unexpected fetch ${url}`);
				}

				const context = {
				  assert,
				  console,
				  document,
				  requests,
				  clearedTimeouts,
				  clearedIntervals,
				  timeoutDelays,
				  control,
				  fetch: async (url, options) => response(url, options),
				  FormData: TestFormData,
				  XMLHttpRequest: TestXhr,
				  setTimeout: (callback, delay) => { timeoutDelays.push(delay); return ++timerId; },
				  clearTimeout: id => clearedTimeouts.push(id),
				  setInterval: () => ++timerId,
				  clearInterval: id => clearedIntervals.push(id),
				  requestAnimationFrame: callback => callback(),
				  window: { crypto:{ randomUUID:() => 'uuid-1' }, open:() => null, toastTimer:null },
				  crypto: { randomUUID:() => 'uuid-1' },
				  URL: { createObjectURL:() => 'blob:test', revokeObjectURL() {} },
				  Notification: function Notification() {}
				};
				context.window.document = document;
				context.globalThis = context;
				vm.createContext(context);

				await vm.runInContext(script + `
				(async () => {
				  const phoneContact = { id:'contact-1', displayName:'Buyer', channels:['chatapp','phone'], points:[
					{ id:'chatapp:whatsapp:8613800000000', channel:'chatapp', type:'phone', value:'8613800000000' },
					{ id:'phone:8613800000000', channel:'phone', type:'phone', value:'8613800000000', label:'8613800000000' }
				  ] };
				  state.contacts = [phoneContact, { id:'contact-2', displayName:'No phone', channels:['wecom'], points:[] }];
				  state.selectedPointId = 'contact-1';
				  state.selectedChannel = 'callRecord';
				  state.wecomAuth = { viewerAuthToken:'viewer-token' };
				  state.wecomAuthExpiresAt = Date.now() + 60000;
				  state.selectedPointId = '';
				  state.selectedChannel = 'phoneRepository';
				  renderComposer();
				  await renderPhoneRepositoryPanel();
				  assert.match($('phoneRepositoryItems').innerHTML, /回电/);
				  state.selectedPointId = 'contact-1';
				  state.selectedChannel = 'phoneRepository';
				  renderComposer();
				  assert.equal(state.selectedChannel, 'phoneRepository',
					  'phone repository tab must remain selected after rendering');

				  state.selectedChannel = 'phone';
				  renderComposer();
				  assert.doesNotMatch($('composer').innerHTML, /data-channel="phone"/);
				  assert.match($('composer').innerHTML, /data-channel="callRecord"/);
				  assert.equal(state.selectedChannel, 'chatapp');
				  state.selectedChannel = 'callRecord';
				  renderCallRecordUploadPanel(phoneContact);
				  assert.match($('sendPanel').innerHTML, /value="phone:8613800000000"/);
				  assert.ok($('sendPanel').innerHTML.includes('>8613800000000</option>'));
				  assert.doesNotMatch($('sendPanel').innerHTML, /unknown/);
				  $('callFile').files = [{ name:'call.mp3', size:123 }];
				  $('callDirection').value = 'inbound';
				  $('callOccurredAt').value = '2026-08-01T10:00';
				  $('callContactId').value = 'contact-1';
				  $('callPhonePoint').disabled = false;
				  $('callPhonePoint').value = 'phone:8613800000000';
				  $('callNote').value = '首次回电';
				  const upload = uploadCallRecord();
				  assert.equal(JSON.stringify(upload.form.entries.map(entry => entry[0])), JSON.stringify(['direction','occurredAt','clientRequestId','phonePointId','note','file']));
				  assert.equal(upload.form.entries.find(entry => entry[0] === 'phonePointId')[1], 'phone:8613800000000');
				  assert.equal(upload.form.entries.find(entry => entry[0] === 'note')[1], '首次回电');
				  assert.equal(upload.headers['X-WeCom-Viewer-Auth'], 'viewer-token');
				  assert.equal(upload.headers['Content-Type'], undefined);
				  upload.status = 201;
				  upload.responseText = JSON.stringify({ callRecordId:'call-1', state:'queued', clientRequestId:'uuid-1' });
				  await upload.onload();
				  assert.match($('thread').innerHTML, /class="call-card/);
				  assert.match($('thread').innerHTML, /排队中/);
				  const uploadedTimeline = requests.findLast(item => item.url.includes('/timeline?limit='));
				  assert.equal(uploadedTimeline.options.headers['X-WeCom-Viewer-Auth'], 'viewer-token');

				  state.selectedPointId = 'contact-2';
				  renderCallRecordUploadPanel(state.contacts[1]);
				  assert.match($('sendPanel').innerHTML, /未绑定电话/);
				  $('callFile').files = [{ name:'call.mp3', size:123 }];
				  $('callDirection').value = 'outbound';
				  $('callOccurredAt').value = '2026-08-01T11:00';
				  $('callPhonePoint').value = '';
				  const noPhoneUpload = uploadCallRecord();
				  assert.equal(noPhoneUpload, undefined);

				  state.contacts = [phoneContact];
				  state.selectedPointId = 'contact-1';
				  renderCallRecordUploadPanel(phoneContact);
				  $('callContactId').value = 'contact-new';
				  $('callContactName').value = '新联系人';
				  $('callPhonePoint').value = 'phone:1390000000000';
				  $('callDirection').value = 'inbound';
				  $('callOccurredAt').value = '2026-08-01T12:00';
				  $('callFile').files = [{ name:'call.mp3', size:123 }];
				  const newContactUpload = uploadCallRecord();
				  assert.equal(typeof newContactUpload.then, 'function');
				  await newContactUpload;
				  assert.ok(requests.some(item => item.url.endsWith('/api/v1/phone-contacts')));

				  state.selectedPointId = 'contact-1';
				  renderCallRecordUploadPanel(phoneContact);
				  $('callFile').files = [{ name:'call.mp3', size:123 }];
				  $('callDirection').value = 'inbound';
				  $('callOccurredAt').value = '2026-08-01T10:00';
				  $('callPhonePoint').disabled = false;
				  $('callPhonePoint').value = 'phone:8613800000000';
				  state.wecomAuthExpiresAt = 0;
				  let expiredAuthError;
				  try { currentWeComAuth(); } catch (error) { expiredAuthError = error; }
				  assert.equal(expiredAuthError.status, 401);
				  assert.equal(expiredAuthError.code, 'WECOM_VIEWER_AUTH_EXPIRED');
				  let expiredUpload;
				  assert.doesNotThrow(() => { expiredUpload = uploadCallRecord(); });
				  assert.equal(expiredUpload, undefined);
				  assert.equal($('uploadCallRecordButton').disabled, false);
				  state.wecomAuthExpiresAt = Date.now() + 60000;

				  const threadBeforeDetail = $('thread').innerHTML;
				  control.failAudioSession = true;
				  control.deferAudioSession = true;
				  const staleAudioAuthorization = openCallRecordDetail('call-1');
				  for (let tick = 0; tick < 10 && typeof control.resolveAudioSession !== 'function'; tick++) await Promise.resolve();
				  assert.equal(typeof control.resolveAudioSession, 'function');
				  state.selectedPointId = 'contact-2';
				  state.selectedCallRecordId = 'call-4';
				  $('detail').innerHTML = '<div>其他电话</div>';
				  const currentCallAudioError = $('callAudioError');
				  control.resolveAudioSession();
				  await staleAudioAuthorization;
				  assert.equal(currentCallAudioError.textContent, '', 'stale audio authorization errors must not overwrite the current detail');
				  control.deferAudioSession = false;
				  state.selectedPointId = 'contact-1';
				  control.failAudioSession = true;
				  await openCallRecordDetail('call-1');
				  assert.match($('detail').innerHTML, /完整转录/);
				  assert.match($('callAudioError').textContent, /播放授权不可用/);
				  control.failAudioSession = false;
				  await openCallRecordDetail('call-1');
				  assert.equal($('thread').innerHTML, threadBeforeDetail);
				  assert.match($('detail').innerHTML, /完整转录/);
				  assert.ok($('callAudio').getAttribute('src').endsWith('/api/v1/call-records/call-1/audio'));
				  const audioBeforeQueuedPoll = $('callAudio');
				  await refreshCallRecordDetail('call-1');
				  assert.equal($('callAudio'), audioBeforeQueuedPoll, 'queued polling must preserve the active audio element');
				  $('callAudio').currentTime = 3;
				  await $('callAudio').play();
				  control.detailState = 'completed';
				  await refreshCallRecordDetail('call-1');
				  assert.equal($('callAudio'), audioBeforeQueuedPoll, 'terminal polling must preserve the active audio element');
				  assert.equal($('callAudio').currentTime, 3);
				  assert.equal($('callAudio').paused, false);
				  assert.match($('detail').innerHTML, /已完成/);
				  control.detailState = 'queued';
				  await refreshCallRecordDetail('call-1');
				  $('callAudio').currentTime = 4;
				  const sessionsBeforeRecovery = requests.filter(item => item.url.endsWith('/audio-sessions')).length;
				  await $('callAudio').onerror();
				  assert.equal($('callAudio').currentTime, 4);
				  await $('callAudio').onerror();
				  assert.equal(requests.filter(item => item.url.endsWith('/audio-sessions')).length, sessionsBeforeRecovery + 1);

				  control.failDetail = true;
				  const detailPollDelayStart = timeoutDelays.length;
				  for (let attempt = 0; attempt < CALL_DETAIL_POLL_MAX_FAILURES + 1; attempt++) await refreshCallRecordDetail('call-1');
				  assert.equal(JSON.stringify(timeoutDelays.slice(detailPollDelayStart)), JSON.stringify([3000,6000,12000,24000]));
				  assert.equal(state.callDetailPollFailures, CALL_DETAIL_POLL_MAX_FAILURES);
				  assert.equal(state.callDetailPollTimer, null, 'detail polling must stop after bounded failures');
				  assert.match($('callDetailUpdateError').textContent, /已停止自动更新/);
				  control.failDetail = false;
				  await refreshCallRecordDetail('call-1');
				  assert.equal($('callDetailUpdateError').textContent, '', 'successful polling must clear the previous update error');

				  const queuedDetail = state.callDetail;
				  state.callDetail = { ...queuedDetail, transcription:{ state:'failed', result:queuedDetail.transcription.result, error:{ retryable:true, message:'失败' } } };
				  renderCallRecordDetail(state.callDetail);
				  control.deferMutation = true;
				  const contactTwoTimelineBeforeRetry = requests.filter(item => item.url.includes('/contacts/contact-2/timeline')).length;
				  const staleRetry = retryCallRecord();
				  await Promise.resolve();
				  await Promise.resolve();
				  assert.equal(typeof control.resolveMutation, 'function');
				  state.selectedPointId = 'contact-2';
				  state.selectedCallRecordId = 'call-2';
				  const newerSelection = { id:'call-2' };
				  state.callDetail = newerSelection;
				  control.resolveMutation();
				  await staleRetry;
				  assert.equal(state.callDetail, newerSelection, 'stale retry response must not replace the current detail');
				  assert.equal(requests.filter(item => item.url.includes('/contacts/contact-2/timeline')).length, contactTwoTimelineBeforeRetry);
				  control.deferMutation = false;
				  state.selectedPointId = 'contact-1';
				  state.selectedCallRecordId = 'call-1';
				  state.callDetail = queuedDetail;

				  renderCallRecordDetail(state.callDetail);
				  $('callRevisionText').value = '人工修订文本';
				  control.deferMutation = true;
				  control.resolveMutation = undefined;
				  const contactTwoTimelineBeforeRevision = requests.filter(item => item.url.includes('/contacts/contact-2/timeline')).length;
				  const staleRevision = reviseCallRecord();
				  await Promise.resolve();
				  await Promise.resolve();
				  assert.equal(typeof control.resolveMutation, 'function');
				  state.selectedPointId = 'contact-2';
				  state.selectedCallRecordId = 'call-3';
				  const latestSelection = { id:'call-3' };
				  state.callDetail = latestSelection;
				  control.resolveMutation();
				  await staleRevision;
				  assert.equal(state.callDetail, latestSelection, 'stale revision response must not replace the current detail');
				  assert.equal(requests.filter(item => item.url.includes('/contacts/contact-2/timeline')).length, contactTwoTimelineBeforeRevision);
				  control.deferMutation = false;
				  state.selectedPointId = 'contact-1';
				  state.selectedCallRecordId = 'call-1';
				  state.callDetail = queuedDetail;

				  const pollTimer = state.callDetailPollTimer;
				  const renewTimer = state.callAudioRenewTimer;
				  await selectContact('contact-2');
				  assert.ok(clearedTimeouts.includes(pollTimer));
				  assert.ok(clearedIntervals.includes(renewTimer));
				  assert.equal(state.selectedCallRecordId, '');
				})()
				`, context);

				console.log('frontend phone call behavior ok');
				""";
	}

    private static void appWiresCallRuntimeBeforeLegacyRoutesAndClosesItBeforeServer()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/crmforlogistics/messagecenter/App.java"), StandardCharsets.UTF_8);
        assertContains(source, "CallRecordRuntime.open(config, store, events::publish)");
        assertContains(source, "callRuntime.available()");
        assertContains(source, "new ContactTimelineService(store, callRuntime.service())");
        assertContains(source, "new CallAudioSessionService(config)");
        assertContains(source, "if (finalCallRecordHttp != null && finalCallRecordHttp.handle(exchange)) return;");
        assertContains(source, "CallRecordHttpAdapter.matchesRoute(exchange.getRequestURI().getRawPath())");
        assertContains(source, "closeCallRuntime(callRuntime);");
        assertNotContains(source, "rawPath.startsWith(\"/api/v1/contacts/\")");
        assertContains(source, "void publish(CallRecordEvent event)");
        assertContains(source, "event.callRecordId()");
        assertContains(source, "event.contactAnchorPointId()");
        assertContains(source, "event.state()");
        assertContains(source, "event.version()");
        int shutdownHook = source.indexOf("message-center-shutdown");
        int hookStart = source.lastIndexOf("addShutdownHook", shutdownHook);
        int closeRuntime = source.indexOf("closeCallRuntime(finalCallRuntime)", hookStart);
        int stopServer = source.indexOf("server.stop(0)", hookStart);
        if (closeRuntime < 0 || stopServer < 0 || closeRuntime >= stopServer) {
            throw new AssertionError("call runtime must close before HTTP server stops");
        }
    }

    @Test
    void phoneIdentityUsesTheSharedContactProjection() throws Exception {
        projectsPhoneIdentityAcrossMergeAndSplit();
    }

    @Test
    void phoneOnlyContactsAreOwnedByTheUnifiedContactStore() throws Exception {
        createsPhoneOnlyContactGroupAndPersistsDisplayName();
        rebindsPhonePointIdempotently();
        rejectsPhonePointBoundToAnotherContactGroup();
        listsPhoneOnlyContactWithoutMessages();
    }

    @Test
    void phoneIdentityRejectsFiveDigits() throws Exception {
        assertInvalidPhoneIdentity("12345");
    }

    @Test
    void phoneIdentityRejectsTwentyOneDigits() throws Exception {
        assertInvalidPhoneIdentity("123456789012345678901");
    }

    @Test
    void phoneIdentityRejectsLettersMixedWithDigits() throws Exception {
        assertInvalidPhoneIdentity("abc123456");
    }

    private static void assertInvalidPhoneIdentity(String phoneNumber) throws Exception {
        assertEquals("", ContactPointUtil.normalizePointId("phone:" + phoneNumber));
        Path dir = Files.createTempDirectory("message-center-phone-validation-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> store.ensurePhoneContact("", phoneNumber, "Invalid"));
        assertEquals("PHONE_NUMBER_INVALID", exception.getMessage());
    }

    @Test
    void ensuringPhoneOnSecondaryPointReturnsPrimaryProjection() throws Exception {
        Path dir = Files.createTempDirectory("message-center-phone-secondary-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(emailData.resolve("inbox.jsonl"), "{"
                + "\"id\":\"mail-secondary-1\","
                + "\"direction\":\"in\","
                + "\"contactEmail\":\"buyer@example.com\","
                + "\"contactName\":\"Buyer\","
                + "\"subject\":\"Existing contact\","
                + "\"sentDate\":\"2026-08-01T01:00:00Z\","
                + "\"bodyText\":\"Body\"}\n", StandardCharsets.UTF_8);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        store.mergeContacts("email:buyer@example.com", "wecom:buyer");

        UnifiedContact contact = store.ensurePhoneContact("wecom:buyer", "13800000000", "ignored");

        assertEquals("email:buyer@example.com", contact.id);
        assertTrue(contact.points.stream().anyMatch(point -> "phone:13800000000".equals(point.id)),
                "primary projection must include the phone bound through its secondary point");
    }

    @Test
    void concurrentDistinctPhoneBindingsDoNotLoseUpdates() throws Exception {
        Path dir = Files.createTempDirectory("message-center-phone-distinct-concurrency-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        int taskCount = 12;
        List<String> primaryPoints = new ArrayList<>();
        for (int index = 0; index < taskCount; index++) {
            primaryPoints.add(store.ensurePhoneContact("", "130000000" + String.format("%02d", index),
                    "Contact " + index).id);
        }

        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < taskCount; index++) {
                String primaryPoint = primaryPoints.get(index);
                String newPhone = "139000000" + String.format("%02d", index);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent bindings did not receive the start signal");
                    }
                    return store.bindPhonePoint(primaryPoint, newPhone);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "all concurrent bindings must be ready");
            start.countDown();
            for (int index = 0; index < taskCount; index++) {
                String expectedPoint = "phone:139000000" + String.format("%02d", index);
                assertEquals(expectedPoint, futures.get(index).get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        for (int index = 0; index < taskCount; index++) {
            String expectedPoint = "phone:139000000" + String.format("%02d", index);
            assertTrue(store.contactGroup(primaryPoints.get(index)).contains(expectedPoint),
                    "concurrent phone binding was lost: " + expectedPoint);
        }
    }

    @Test
    void concurrentSamePhoneBindingHasExactlyOneWinner() throws Exception {
        Path dir = Files.createTempDirectory("message-center-phone-conflict-concurrency-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        int taskCount = 12;
        List<String> primaryPoints = new ArrayList<>();
        for (int index = 0; index < taskCount; index++) {
            primaryPoints.add(store.ensurePhoneContact("", "131000000" + String.format("%02d", index),
                    "Contact " + index).id);
        }

        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (String primaryPoint : primaryPoints) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("concurrent bindings did not receive the start signal");
                    }
                    try {
                        store.bindPhonePoint(primaryPoint, "13800000000");
                        return "success";
                    } catch (IllegalStateException exception) {
                        return exception.getMessage();
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "all concurrent bindings must be ready");
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, (int) results.stream().filter("success"::equals).count());
            assertEquals(taskCount - 1,
                    (int) results.stream().filter("PHONE_POINT_CONFLICT"::equals).count());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void createsPhoneOnlyContactGroupAndPersistsDisplayName() throws Exception {
        Path dir = Files.createTempDirectory("message-center-phone-only-contact-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);

        UnifiedContact contact = testStore(dir, emailData, chatData.resolve("messages.jsonl"))
                .ensurePhoneContact("", "+86 138-0000-0000", "采购联系人");

        assertEquals("phone:8613800000000", contact.id);
        assertEquals("采购联系人", contact.displayName);
        assertEquals("phone:8613800000000", contact.points.get(0).id);
        assertEquals("phone", contact.points.get(0).channel);
        assertEquals(0, contact.messageCount);
        assertContains(Files.readString(dir.resolve("contact-groups.jsonl")), "采购联系人");
    }

    private static void rebindsPhonePointIdempotently() throws Exception {
        Path dir = Files.createTempDirectory("message-center-phone-rebind-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        UnifiedContact contact = store.ensurePhoneContact("", "13800000000", "采购联系人");

        assertEquals("phone:13800000000", store.bindPhonePoint(contact.id, "138-0000-0000"));
        assertEquals("1", Integer.toString(store.contactGroup(contact.id).size()));
        assertEquals("1", Integer.toString(store.contacts().size()));
    }

    private static void rejectsPhonePointBoundToAnotherContactGroup() throws Exception {
        Path dir = Files.createTempDirectory("message-center-phone-conflict-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        UnifiedContact first = store.ensurePhoneContact("", "13800000000", "采购联系人");
        UnifiedContact second = store.ensurePhoneContact("", "13900000000", "运输联系人");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> store.bindPhonePoint(second.id, "13800000000"));
        assertEquals("PHONE_POINT_CONFLICT", exception.getMessage());
        assertEquals("phone:13800000000", store.contactGroup(first.id).get(0));
        assertEquals("phone:13900000000", store.contactGroup(second.id).get(0));
    }

    private static void listsPhoneOnlyContactWithoutMessages() throws Exception {
        Path dir = Files.createTempDirectory("message-center-phone-only-list-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        store.ensurePhoneContact("", "13800000000", "采购联系人");

        List<UnifiedContact> contacts = store.contacts();
        assertEquals("1", Integer.toString(contacts.size()));
        assertEquals("采购联系人", contacts.get(0).displayName);
        assertEquals("", contacts.get(0).lastText);
        assertEquals(0, contacts.get(0).messageCount);
        assertEquals("phone", String.join(",", contacts.get(0).channels));
    }

    private static void projectsPhoneIdentityAcrossMergeAndSplit() throws Exception {
        assertEquals("phone:8613800000000",
                ContactPointUtil.normalizePointId("PHONE:+86 138-0000-0000"));
        assertEquals("", ContactPointUtil.normalizePointId("phone:---"));

        ContactPoint projected = ContactPointUtil.fromId(
                "phone:+86 138-0000-0000", null);
        assertEquals("phone:8613800000000", projected.id);
        assertEquals("phone", projected.channel);
        assertEquals("phone", projected.type);
        assertEquals("8613800000000", projected.value);
        assertEquals("8613800000000", projected.label);

        Path dir = Files.createTempDirectory("message-center-phone-point-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(emailData.resolve("inbox.jsonl"), "{"
                + "\"id\":\"mail-phone-1\","
                + "\"direction\":\"in\","
                + "\"contactEmail\":\"buyer@example.com\","
                + "\"contactName\":\"Buyer\","
                + "\"subject\":\"Call follow-up\","
                + "\"sentDate\":\"2026-07-30T01:00:00Z\","
                + "\"bodyText\":\"Body\"}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(
                dir, emailData, chatData.resolve("messages.jsonl"));
        store.mergeContacts(
                "email:buyer@example.com", "phone:+86 138-0000-0000");
        UnifiedContact merged = store.contacts().stream()
                .filter(contact -> "email:buyer@example.com".equals(contact.id))
                .findFirst().orElseThrow();
        ContactPoint phone = merged.points.stream()
                .filter(point -> "phone:8613800000000".equals(point.id))
                .findFirst().orElseThrow();
        assertEquals("phone", phone.channel);
        assertEquals("phone", phone.type);

        store.splitContact(
                "email:buyer@example.com", "phone:8613800000000");
        assertEquals("phone:8613800000000",
                store.contactGroup("phone:+86 138-0000-0000").get(0));
    }

    private static void chatAppTemplateMessagesUseTemplateRequestType() {
        Config config = new Config(Map.of("CHATAPP_TYPE", "message"));

        assertEquals("template", ChatAppSender.templateRequestType(config));
    }

    private static void chatAppTemplateRequestsOmitMessageType() {
        Config config = new Config(Map.of(
                "CUST_SPACE_ID", "space-1",
                "CHATAPP_FROM", "8613000000000",
                "CHATAPP_TYPE", "message",
                "CHATAPP_MESSAGE_TYPE", "text",
                "CHATAPP_TEMPLATE_MESSAGE_TYPE", "text"
        ));

        SendChatappMessageRequest request = ChatAppSender.buildTemplateRequest(config, "8613111111111", "tpl-1",
                "shipping_notice", "zh_CN", Map.of("text", "Alex"), "task-1");

        assertEquals("template", request.getType());
        assertNull(request.getMessageType(), "template request must not send messageType");
        assertEquals("tpl-1", request.getTemplateCode());
    }

    private static void mergesEmailAndChatAppContactPointsIntoOneTimeline() throws Exception {
        Path dir = Files.createTempDirectory("message-center-merge-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);

        Files.writeString(emailData.resolve("inbox.jsonl"), "{"
                + "\"id\":\"mail-1\","
                + "\"storedAt\":\"2026-07-10T01:00:00Z\","
                + "\"direction\":\"in\","
                + "\"contactEmail\":\"buyer@example.com\","
                + "\"contactName\":\"Buyer\","
                + "\"from\":\"Buyer <buyer@example.com>\","
                + "\"to\":\"seller@example.com\","
                + "\"subject\":\"Need quote\","
                + "\"sentDate\":\"2026-07-10T01:00:00Z\","
                + "\"summary\":\"Need quote body\","
                + "\"bodyText\":\"Need quote body\","
                + "\"messageId\":\"<mail-1@example.com>\""
                + "}\n", StandardCharsets.UTF_8);

        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-1\","
                + "\"direction\":\"inbound\","
                + "\"timestamp\":\"2026-07-10T01:05:00Z\","
                + "\"from\":\"8613800000000\","
                + "\"to\":\"8613266259485\","
                + "\"text\":\"{\\\"text\\\":\\\"WhatsApp hello\\\"}\","
                + "\"raw\":\"{}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        assertEquals("2", Integer.toString(store.contacts().size()));

        store.mergeContacts("email:buyer@example.com", "chatapp:whatsapp:8613800000000");

        List<UnifiedContact> contacts = store.contacts();
        assertEquals("1", Integer.toString(contacts.size()));
        assertEquals("2", Integer.toString(contacts.get(0).points.size()));
        assertEquals("email,chatapp", String.join(",", contacts.get(0).channels));
        assertEquals("inbound", contacts.get(0).lastDirection);
        assertEquals("chatapp", contacts.get(0).lastChannel);

        List<UnifiedMessage> thread = store.thread("email:buyer@example.com");
        assertEquals("2", Integer.toString(thread.size()));
        assertEquals("email", thread.get(0).channel);
        assertEquals("chatapp", thread.get(1).channel);
        assertEquals("WhatsApp hello", thread.get(1).text);
    }

    private static void storesContactGroupProfileWithoutLosingMergedPoints() throws Exception {
        Path dir = Files.createTempDirectory("message-center-remark-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(emailData.resolve("inbox.jsonl"), "{\"id\":\"mail-1\",\"direction\":\"in\",\"contactEmail\":\"buyer@example.com\",\"contactName\":\"Buyer\",\"subject\":\"Need quote\",\"sentDate\":\"2026-07-10T01:00:00Z\",\"bodyText\":\"Body\"}\n", StandardCharsets.UTF_8);
        Files.writeString(chatData.resolve("messages.jsonl"), "{\"id\":\"chat-1\",\"direction\":\"inbound\",\"timestamp\":\"2026-07-10T01:05:00Z\",\"from\":\"8613800000000\",\"to\":\"8613266259485\",\"text\":\"hi\",\"raw\":\"{}\"}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        store.mergeContacts("email:buyer@example.com", "chatapp:whatsapp:8613800000000");
        store.updateContactProfile("email:buyer@example.com", "华南买家", List.of("VIP", "需跟进"));

        List<UnifiedContact> contacts = new UnifiedMessageStore(testConfig(dir, emailData, chatData.resolve("messages.jsonl"))).contacts();
        assertEquals("1", Integer.toString(contacts.size()));
        assertEquals("华南买家", contacts.get(0).remark);
        assertEquals("华南买家", contacts.get(0).displayName);
        assertEquals("2", Integer.toString(contacts.get(0).points.size()));
        assertEquals("VIP,需跟进", String.join(",", contacts.get(0).tags));
        assertEquals("email:buyer@example.com", contacts.get(0).points.get(0).id);
        assertEquals("chatapp:whatsapp:8613800000000", contacts.get(0).points.get(1).id);
    }

    private static void splitsMergedContactPointWithoutDeletingRawMessages() throws Exception {
        Path dir = Files.createTempDirectory("message-center-split-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(emailData.resolve("inbox.jsonl"), "{\"id\":\"mail-1\",\"direction\":\"in\",\"contactEmail\":\"buyer@example.com\",\"contactName\":\"Buyer\",\"subject\":\"Need quote\",\"sentDate\":\"2026-07-10T01:00:00Z\",\"bodyText\":\"Body\"}\n", StandardCharsets.UTF_8);
        Files.writeString(chatData.resolve("messages.jsonl"), "{\"id\":\"chat-1\",\"direction\":\"inbound\",\"timestamp\":\"2026-07-10T01:05:00Z\",\"from\":\"8613800000000\",\"to\":\"8613266259485\",\"text\":\"hi\",\"raw\":\"{}\"}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        store.mergeContacts("email:buyer@example.com", "chatapp:whatsapp:8613800000000");
        store.splitContact("email:buyer@example.com", "chatapp:whatsapp:8613800000000");

        assertEquals("2", Integer.toString(store.contacts().size()));
        assertEquals("1", Integer.toString(store.thread("email:buyer@example.com").size()));
        assertEquals("1", Integer.toString(store.thread("chatapp:whatsapp:8613800000000").size()));
    }

    private static void splitsLegacyEmailContactGroupWhenOnlyOnePointRemains() throws Exception {
        Path dir = Files.createTempDirectory("message-center-legacy-email-split-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(emailData.resolve("inbox.jsonl"), ""
                + "{\"id\":\"mail-1\",\"direction\":\"in\",\"contactEmail\":\"a@example.com\",\"contactName\":\"A\",\"subject\":\"A\",\"sentDate\":\"2026-07-10T01:00:00Z\",\"bodyText\":\"A\"}\n"
                + "{\"id\":\"mail-2\",\"direction\":\"in\",\"contactEmail\":\"b@example.com\",\"contactName\":\"B\",\"subject\":\"B\",\"sentDate\":\"2026-07-10T01:01:00Z\",\"bodyText\":\"B\"}\n",
                StandardCharsets.UTF_8);
        Files.writeString(emailData.resolve("contact-groups.jsonl"),
                "{\"primaryEmail\":\"a@example.com\",\"emails\":[\"a@example.com\",\"b@example.com\"]}\n",
                StandardCharsets.UTF_8);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        assertEquals("1", Integer.toString(store.contacts().size()));

        store.splitContact("email:a@example.com", "email:b@example.com");

        assertEquals("2", Integer.toString(store.contacts().size()));
        assertEquals("1", Integer.toString(store.contactGroup("email:a@example.com").size()));
        assertEquals("email:a@example.com", store.contactGroup("email:a@example.com").get(0));
        assertEquals("1", Integer.toString(store.contactGroup("email:b@example.com").size()));
        assertEquals("email:b@example.com", store.contactGroup("email:b@example.com").get(0));
    }

    private static void threadPageReturnsRecentTenMessagesAndCursor() throws Exception {
        Path dir = Files.createTempDirectory("message-center-thread-page-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        StringBuilder jsonl = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            jsonl.append("{")
                    .append("\"id\":\"chat-").append(i).append("\",")
                    .append("\"direction\":\"inbound\",")
                    .append("\"timestamp\":\"2026-07-10T01:")
                    .append(String.format("%02d", i)).append(":00Z\",")
                    .append("\"from\":\"8613800000000\",")
                    .append("\"to\":\"8613266259485\",")
                    .append("\"text\":\"message-").append(i).append("\",")
                    .append("\"raw\":\"{}\"")
                    .append("}\n");
        }
        Files.writeString(chatData.resolve("messages.jsonl"), jsonl.toString(), StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));
        UnifiedMessageStore.ThreadPage firstPage = store.threadPage("chatapp:whatsapp:8613800000000", "", 10);

        assertEquals(10, firstPage.items.size());
        assertEquals("message-16", firstPage.items.get(0).text);
        assertEquals("message-25", firstPage.items.get(firstPage.items.size() - 1).text);
        assertTrue(firstPage.nextCursor != null && !firstPage.nextCursor.isBlank(),
                "first page must expose nextCursor for older messages");
        assertTrue(firstPage.threadRevision != null && !firstPage.threadRevision.isBlank(),
                "first page must expose a threadRevision");

        UnifiedMessageStore.ThreadPage secondPage = store.threadPage(
                "chatapp:whatsapp:8613800000000", firstPage.nextCursor, 10);
        assertEquals(10, secondPage.items.size());
        assertEquals("message-6", secondPage.items.get(0).text);
        assertEquals("message-15", secondPage.items.get(secondPage.items.size() - 1).text);
        assertTrue(secondPage.nextCursor != null && !secondPage.nextCursor.isBlank(),
                "second page must expose nextCursor for the oldest remaining messages");

        UnifiedMessageStore.ThreadPage thirdPage = store.threadPage(
                "chatapp:whatsapp:8613800000000", secondPage.nextCursor, 10);
        assertEquals(5, thirdPage.items.size());
        assertEquals("message-1", thirdPage.items.get(0).text);
        assertEquals("message-5", thirdPage.items.get(thirdPage.items.size() - 1).text);
        assertNull(thirdPage.nextCursor, "oldest page must not expose nextCursor");
    }

    private static void threadPageUsesDeletedCursorAsTimelineBoundary() throws Exception {
        Path dir = Files.createTempDirectory("message-center-deleted-thread-cursor-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        StringBuilder jsonl = new StringBuilder();
        for (int i = 1; i <= 16; i++) {
            jsonl.append("{")
                    .append("\"id\":\"chat-").append(i).append("\",")
                    .append("\"direction\":\"inbound\",")
                    .append("\"timestamp\":\"2026-07-10T01:")
                    .append(String.format("%02d", i)).append(":00Z\",")
                    .append("\"from\":\"8613800000000\",")
                    .append("\"to\":\"8613266259485\",")
                    .append("\"text\":\"message-").append(i).append("\",")
                    .append("\"raw\":\"{}\"")
                    .append("}\n");
        }
        Path chatFile = chatData.resolve("messages.jsonl");
        Files.writeString(chatFile, jsonl.toString(), StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatFile);
        UnifiedMessageStore.ThreadPage firstPage = store.threadPage("chatapp:whatsapp:8613800000000", "", 10);
        assertEquals(10, firstPage.items.size());
        assertEquals("message-7", firstPage.items.get(0).text);
        assertEquals(16, firstPage.messageCount);

        StringBuilder remainingOlder = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            remainingOlder.append("{")
                    .append("\"id\":\"chat-").append(i).append("\",")
                    .append("\"direction\":\"inbound\",")
                    .append("\"timestamp\":\"2026-07-10T01:")
                    .append(String.format("%02d", i)).append(":00Z\",")
                    .append("\"from\":\"8613800000000\",")
                    .append("\"to\":\"8613266259485\",")
                    .append("\"text\":\"message-").append(i).append("\",")
                    .append("\"raw\":\"{}\"")
                    .append("}\n");
        }
        Files.writeString(chatFile, remainingOlder.toString(), StandardCharsets.UTF_8);

        UnifiedMessageStore.ThreadPage olderPage = store.threadPage(
                "chatapp:whatsapp:8613800000000", firstPage.nextCursor, 10);
        assertEquals(6, olderPage.items.size());
        assertEquals("message-1", olderPage.items.get(0).text);
        assertEquals("message-6", olderPage.items.get(olderPage.items.size() - 1).text);
        assertEquals(6, olderPage.messageCount);
        assertNull(olderPage.nextCursor, "deleted cursor should not permanently hide older messages");
    }

    private static void threadPageRevisionChangesWhenMessagesChangeWithoutCountChange() throws Exception {
        Path dir = Files.createTempDirectory("message-center-thread-revision-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Path chatFile = chatData.resolve("messages.jsonl");
        Files.writeString(chatFile, chatMessagesJsonl(1, 10, "original"), StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatFile);
        UnifiedMessageStore.ThreadPage firstPage = store.threadPage("chatapp:whatsapp:8613800000000", "", 10);

        Files.writeString(chatFile, chatMessagesJsonl(1, 9, "original")
                + "{"
                + "\"id\":\"chat-replacement\","
                + "\"direction\":\"inbound\","
                + "\"timestamp\":\"2026-07-10T01:10:30Z\","
                + "\"from\":\"8613800000000\","
                + "\"to\":\"8613266259485\","
                + "\"text\":\"replacement\","
                + "\"raw\":\"{}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore.ThreadPage replacementPage = store.threadPage("chatapp:whatsapp:8613800000000", "", 10);

        assertEquals(firstPage.messageCount, replacementPage.messageCount);
        assertNotEquals(firstPage.threadRevision, replacementPage.threadRevision,
                "equal-count replacement must change threadRevision");
    }

    private static void databaseThreadPageUsesLimitPlusOneAndUuidCursor() throws Exception {
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID contactId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID oldestId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID cursorId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID newestId = UUID.fromString("00000000-0000-0000-0000-000000000012");
        Instant cursorTimestamp = Instant.parse("2026-07-10T01:01:00Z");
        List<Integer> receivedLimits = new ArrayList<>();
        List<MessageCursor> receivedCursors = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        MessageRepository repository = new MessageRepository() {
            @Override
            public List<UnifiedMessage> unifiedTimeline(
                    UUID receivedUserId, UUID receivedContactId, MessageCursor cursor, int limit) {
                throw unsupported();
            }

            @Override
            public UnifiedTimelineSnapshot unifiedTimelinePage(
                    UUID receivedUserId, UUID receivedContactId, MessageCursor cursor, int limit) {
                assertEquals(userId.toString(), receivedUserId.toString());
                assertEquals(contactId.toString(), receivedContactId.toString());
                receivedLimits.add(limit);
                receivedCursors.add(cursor);
                if (calls.getAndIncrement() == 0) {
                    return new UnifiedTimelineSnapshot(List.of(
                            threadMessage(oldestId, "2026-07-10T01:00:00Z", "oldest-extra"),
                            threadMessage(cursorId, cursorTimestamp.toString(), "page-one-first"),
                            threadMessage(newestId, cursorTimestamp.toString(), "page-one-last")),
                            3, "db-revision-1");
                }
                return new UnifiedTimelineSnapshot(List.of(
                        threadMessage(oldestId, "2026-07-10T01:00:00Z", "page-two-first"),
                        threadMessage(UUID.fromString("00000000-0000-0000-0000-000000000009"),
                                "2026-07-10T01:00:00Z", "page-two-last")),
                        3, "db-revision-1");
            }

            @Override
            public int unifiedTimelineCount(UUID receivedUserId, UUID receivedContactId) {
                throw unsupported();
            }

            @Override
            public String unifiedTimelineRevision(UUID receivedUserId, UUID receivedContactId) {
                throw unsupported();
            }

            @Override public UUID getOrCreateConversation(UUID accountId, UUID identityId) { throw unsupported(); }
            @Override public MessageWriteResult insert(MessageDraft draft) { throw unsupported(); }
            @Override public void appendStatus(UUID messageId, MessageStatusEvent event) { throw unsupported(); }
            @Override public List<UnifiedMessage> thread(UUID uid, UUID conversationId, MessageCursor cursor, int limit) { throw unsupported(); }
            @Override public Optional<UnifiedMessage> findAuthorized(UUID uid, UUID messageId) { throw unsupported(); }
        };
        ContactRepository contacts = (ContactRepository) java.lang.reflect.Proxy.newProxyInstance(
                ContactRepository.class.getClassLoader(), new Class<?>[]{ContactRepository.class},
                (proxy, method, args) -> { throw unsupported(); });
        UnifiedMessageStore store = new UnifiedMessageStore(contacts, repository, userId);

        UnifiedMessageStore.ThreadPage firstPage = store.threadPage(contactId.toString(), "", 2);

        assertEquals(3, receivedLimits.get(0));
        assertNull(receivedCursors.get(0), "first database page must not have a repository cursor");
        assertEquals(2, firstPage.items.size());
        assertEquals(cursorId.toString(), firstPage.items.get(0).id);
        assertEquals(newestId.toString(), firstPage.items.get(firstPage.items.size() - 1).id);
        assertEquals(3, firstPage.messageCount);
        assertEquals("db-revision-1", firstPage.threadRevision);
        assertTrue(firstPage.nextCursor != null && !firstPage.nextCursor.isBlank(),
                "database page with an extra row must expose nextCursor");

        UnifiedMessageStore.ThreadPage secondPage = store.threadPage(contactId.toString(), firstPage.nextCursor, 2);

        assertEquals(3, receivedLimits.get(receivedLimits.size() - 1));
        assertEquals(cursorTimestamp.toString(), receivedCursors.get(receivedCursors.size() - 1).occurredAt().toString());
        assertEquals(cursorId.toString(), receivedCursors.get(receivedCursors.size() - 1).id().toString());
        assertEquals(2, secondPage.items.size());
        assertNull(secondPage.nextCursor, "final database page must not expose nextCursor");
    }

    private static void threadPageRejectsNonEmptyCursorMissingRequiredFields() throws Exception {
        Path dir = Files.createTempDirectory("message-center-invalid-thread-cursor-test");
        Path emailData = dir.resolve("email");
        Path chatFile = dir.resolve("chatapp/messages.jsonl");
        Files.createDirectories(emailData);
        Files.createDirectories(chatFile.getParent());
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"timestamp\":\"2026-07-10T01:00:00Z\"}".getBytes(StandardCharsets.UTF_8));

        try {
            testStore(dir, emailData, chatFile).threadPage("chatapp:whatsapp:8613800000000", cursor, 2);
            throw new AssertionError("non-empty cursor missing id must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("invalid thread cursor", expected.getMessage());
        }
    }

    private static UnifiedMessage threadMessage(UUID id, String timestamp, String text) {
        UnifiedMessage message = new UnifiedMessage();
        message.id = id.toString();
        message.timestamp = timestamp;
        message.text = text;
        return message;
    }

    private static String chatMessagesJsonl(int start, int end, String prefix) {
        StringBuilder jsonl = new StringBuilder();
        for (int i = start; i <= end; i++) {
            jsonl.append("{")
                    .append("\"id\":\"chat-").append(i).append("\",")
                    .append("\"direction\":\"inbound\",")
                    .append("\"timestamp\":\"2026-07-10T01:")
                    .append(String.format("%02d", i)).append(":00Z\",")
                    .append("\"from\":\"8613800000000\",")
                    .append("\"to\":\"8613266259485\",")
                    .append("\"text\":\"").append(prefix).append("-").append(i).append("\",")
                    .append("\"raw\":\"{}\"")
                    .append("}\n");
        }
        return jsonl.toString();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not used by thread page test");
    }

    private static void wecomViewerSignatureUsesOfficialJsapiAlgorithmAndOriginAllowlist() throws Exception {
        Config config = new Config(Map.of(
                "WECOM_CORP_ID", "ww-test-corp",
                "WECOM_AGENT_ID", "1000247",
                "WECOM_SECRET", "secret",
                "WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:8099,https://crm.example.com"
        ));
        WeComViewerService service = WeComViewerService.forTests(
                config,
                Clock.fixed(Instant.ofEpochSecond(1414587457), ZoneOffset.UTC),
                () -> "Wm3WZYTPz0wzccnW",
                new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "user-1"));

        assertEquals("c97d4ff7bc7e97cd2e0222f9c69ee8bf2fcc3bfe",
                WeComViewerService.makeSignature("sM4AOVdWfPE4DxkXGEs8VMP",
                        "http://mp.weixin.qq.com?params=value",
                        1414587457L,
                        "Wm3WZYTPz0wzccnW"));

        WeComViewerService.JsSdkConfig js = service.jsSdkConfig("http://localhost:8099/?a=1#ignored");
        assertEquals("ww-test-corp", js.corpId());
        assertEquals("1000247", js.agentId());
        assertTrue(js.jsApiList().contains("wwapp.invokeJsApiByCallInfo"),
                "conversation viewer must request wwapp.invokeJsApiByCallInfo");
        assertEquals("1414587457", js.configSignature().timestamp());
        assertEquals("Wm3WZYTPz0wzccnW", js.configSignature().nonceStr());
        assertFalse(js.configSignature().signature().isBlank(), "config signature must be populated");
        assertFalse(js.agentConfigSignature().signature().isBlank(), "agent signature must be populated");

        assertThrows(IllegalArgumentException.class,
                () -> service.jsSdkConfig("https://evil.example.com/page"));
    }

    private static void wecomViewerSessionsAreBoundedUserScopedAndReadMessageReferences() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-viewer-test");
        Path dataFile = dir.resolve("wecom-messages.jsonl");
        Files.writeString(dataFile, ""
                + "{\"msgid\":\"m1\",\"secret_key\":\"s1\",\"external_userid\":\"ext-1\",\"userid\":\"user-1\",\"open_kfid\":\"kf-1\",\"send_time\":1,\"msgtype\":\"text\",\"text\":{\"content\":\"one\"}}\n"
                + "not-json\n"
                + "{\"msgid\":\"m2\",\"secretKey\":\"s2\",\"external_userid\":\"ext-1\",\"wecom_userid\":\"user-1\",\"open_kfid\":\"kf-1\",\"send_time\":2,\"msgtype\":\"text\",\"text\":{\"content\":\"two\"}}\n"
                + "{\"msgid\":\"m4\",\"secret_key\":\"s4\",\"external_userid\":\"ext-1\",\"userid\":\"user-1\",\"open_kfid\":\"kf-1\",\"send_time\":4,\"msgtype\":\"text\",\"text\":{\"content\":\"four\"}}\n"
                + "{\"msgid\":\"m3\",\"secret_key\":\"s3\",\"external_userid\":\"ext-1\",\"userid\":\"user-1\",\"open_kfid\":\"kf-1\",\"send_time\":3,\"msgtype\":\"text\",\"text\":{\"content\":\"three\"}}\n"
                + "{\"msgid\":\"m3\",\"secret_key\":\"s3\",\"external_userid\":\"ext-2\",\"userid\":\"user-2\",\"open_kfid\":\"kf-1\",\"send_time\":3,\"msgtype\":\"text\",\"text\":{\"content\":\"three\"}}\n",
                StandardCharsets.UTF_8);
        Config config = new Config(Map.of(
                "DATA_DIR", dir.toString(),
                "WECOM_DATA_FILE", dataFile.toString(),
                "WECOM_CORP_ID", "ww-test-corp",
                "WECOM_AGENT_ID", "1000247",
                "WECOM_SECRET", "secret",
                "WECOM_VIEWER_SESSION_TTL_SECONDS", "60",
                "WECOM_VIEWER_MAX_MESSAGES", "2",
                "WECOM_VIEWER_SESSION_RATE_LIMIT", "4"
        ));
        WeComViewerService service = WeComViewerService.forTests(
                config,
                Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC),
                () -> "nonce",
                new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "user-1"));

        WeComViewerService.LoginExchangeResponse login = service.exchangeLoginCode("code-1");
        assertEquals("user-1", login.wecomUserId());
        assertFalse(login.viewerAuthToken().isBlank(), "login exchange must return a bounded viewer auth token");
        WeComViewerService.LoginExchangeResponse secondLogin = service.exchangeLoginCode("code-2");

        WeComViewerService.ViewerSessionResponse created = service.createViewerSession(
                "wecom:ext-1", login.viewerAuthToken(), List.of("msg-1"));
        assertEquals(60, created.expiresIn());
        assertFalse(created.viewerSessionId().isBlank(), "viewer session id must be generated");

        assertThrows(SecurityException.class,
                () -> service.createViewerSession("wecom:ext-2", login.viewerAuthToken(), List.of("msg-2")));
        assertThrows(SecurityException.class,
                () -> service.viewerSession(created.viewerSessionId(), secondLogin.viewerAuthToken()));

        WeComViewerService.ViewerSessionDetail detail = service.viewerSession(
                created.viewerSessionId(), login.viewerAuthToken());
        assertEquals("ww-test-corp", detail.corpId());
        assertEquals("1000247", detail.agentId());
        assertEquals(2, detail.messages().size());
        assertEquals("m3", detail.messages().get(0).msgid());
        assertEquals("s3", detail.messages().get(0).secretKey());
        assertEquals("m4", detail.messages().get(1).msgid());
        assertEquals("s4", detail.messages().get(1).secretKey());

        assertThrows(SecurityException.class,
                () -> service.viewerSession(created.viewerSessionId(), "other-token"));
        assertThrows(IllegalArgumentException.class,
                () -> service.viewerSession(created.viewerSessionId(), login.viewerAuthToken()));

        WeComViewerService.ViewerSessionResponse firstViewed = service.createViewerSession(
                "wecom:ext-1", secondLogin.viewerAuthToken(), List.of("msg-1"));
        service.viewerSession(firstViewed.viewerSessionId(), secondLogin.viewerAuthToken());
        WeComViewerService.ViewerSessionResponse latestViewed = service.createViewerSession(
                "wecom:ext-1", secondLogin.viewerAuthToken(), List.of("msg-1"));
        service.viewerSession(latestViewed.viewerSessionId(), secondLogin.viewerAuthToken());
        assertThrows(SecurityException.class,
                () -> service.recordClientEvent("component_error", firstViewed.viewerSessionId(),
                        secondLogin.viewerAuthToken()));
        service.recordClientEvent("component_error", latestViewed.viewerSessionId(),
                secondLogin.viewerAuthToken());
        assertThrows(SecurityException.class,
                () -> service.recordClientEvent("component_error", latestViewed.viewerSessionId(),
                        secondLogin.viewerAuthToken()));

        assertThrows(WeComViewerService.RateLimitException.class,
                () -> service.createViewerSession("wecom:ext-1", login.viewerAuthToken(), List.of("msg-1")));

        String audit = Files.readString(config.wecomViewerAuditFile(), StandardCharsets.UTF_8);
        assertContains(audit, "wecom.viewer.login_exchange");
        assertContains(audit, "wecom.viewer.session_create");
        assertContains(audit, "\"result\":\"denied\"");
        assertContains(audit, "\"result\":\"rate_limited\"");
        assertNotContains(audit, login.viewerAuthToken());
        assertNotContains(audit, "\"secretKey\"");

        WeComViewerService empty = WeComViewerService.forTests(
                config,
                Clock.fixed(Instant.ofEpochSecond(2000), ZoneOffset.UTC),
                () -> "nonce",
                new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "user-1"));
        assertThrows(SecurityException.class,
                () -> empty.viewerSession(created.viewerSessionId(), login.viewerAuthToken()));
    }

    private static void wecomViewerLoginExchangeDoesNotPublishTokenWhenAuditFails() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-viewer-audit-failure-test");
        Path auditFile = dir.resolve("wecom-viewer-audit.jsonl");
        Files.write(auditFile, new byte[4096]);
        Config config = new Config(Map.of(
                "DATA_DIR", dir.toString(),
                "WECOM_VIEWER_AUDIT_FILE", auditFile.toString(),
                "WECOM_VIEWER_AUDIT_MAX_BYTES", "4096"
        ));
        WeComViewerService service = WeComViewerService.forTests(
                config,
                Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC),
                () -> "nonce",
                new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "user-1"));

        assertThrows(IOException.class, () -> service.exchangeLoginCode("code-1"));
        assertEquals(0, service.activeViewerAuthTokenCount());
    }

    private static void wecomViewerRoutesReturnBoundedConfigAndSessionPayloads() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-viewer-route-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Path wecomData = dir.resolve("wecom.jsonl");
        Path chatDataPrivateKey = dir.resolve("wecom-chatdata-private-key.pem");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatDataPrivateKey, "test-key", StandardCharsets.UTF_8);
        Files.writeString(wecomData, ""
                + "{\"msgid\":\"m1\",\"secret_key\":\"s1\",\"external_userid\":\"ext-1\",\"userid\":\"user-1\",\"open_kfid\":\"kf-1\",\"send_time\":1,\"msgtype\":\"text\"}\n"
                + "{\"msgid\":\"m2\",\"secret_key\":\"s2\",\"external_userid\":\"ext-2\",\"userid\":\"user-2\",\"open_kfid\":\"kf-1\",\"send_time\":2,\"msgtype\":\"text\"}\n",
                StandardCharsets.UTF_8);
        Config config = new Config(Map.ofEntries(
                Map.entry("DATA_DIR", dir.toString()),
                Map.entry("EMAIL_DATA_DIR", emailData.toString()),
                Map.entry("CHATAPP_DATA_FILE", chatData.resolve("messages.jsonl").toString()),
                Map.entry("CHATAPP_TEMPLATE_FILE", dir.resolve("templates.json").toString()),
                Map.entry("WECOM_DATA_FILE", wecomData.toString()),
                Map.entry("WECOM_SUITE_ID", "dk-test-suite"),
                Map.entry("WECOM_LOGIN_SUITE_ID", "ww-login-suite"),
                Map.entry("WECOM_LOGIN_SUITE_SECRET", "login-suite-secret"),
                Map.entry("WECOM_LOGIN_AUTH_CORP_ID", "ww-test-corp"),
                Map.entry("WECOM_CHATDATA_PROGRAM_ID", "program-1"),
                Map.entry("WECOM_CHATDATA_ABILITY_ID", "ability-1"),
                Map.entry("WECOM_CHATDATA_PRIVATE_KEY_FILE", chatDataPrivateKey.toString()),
                Map.entry("WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:8099")
        ));
        byte[] installationKey = new byte[32];
        java.util.Arrays.fill(installationKey, (byte) 7);
        WeComAuthorizationStore authorizationStore = WeComAuthorizationStore.forTests(
                dir.resolve("wecom-authorizations.jsonl"),
                CredentialCipher.fromBase64Key(Base64.getEncoder().encodeToString(installationKey)),
                Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));
        authorizationStore.upsertActive("dk-test-suite", "ww-test-corp", "1000247", "permanent-code");
        UnifiedMessageStore store = new UnifiedMessageStore(config);
        WeComViewerService viewer = WeComViewerService.forTests(
                config,
                Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC),
                () -> "nonce",
                new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "user-1"),
                authorizationStore);
        WeComLoginAttemptService loginAttempts = WeComLoginAttemptService.forTests(config, authorizationStore,
                Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC),
                () -> "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        FakeHttpExchange configExchange = new FakeHttpExchange(
                "GET", "/api/v1/wecom/js-sdk-config?url=http%3A%2F%2Flocalhost%3A8099%2F");
        App.routeForTests(configExchange, config, store, viewer);
        assertEquals(403, configExchange.responseCode);

        FakeHttpExchange attemptExchange = new FakeHttpExchange("POST", "/api/v1/wecom/login/attempts");
        attemptExchange.requestBodyJson("{}");
        App.routeForTests(attemptExchange, config, store, viewer, loginAttempts);
        assertEquals(200, attemptExchange.responseCode);
        JsonObject attempt = JsonParser.parseString(attemptExchange.responseText()).getAsJsonObject();
        assertContains(attemptExchange.responseText(), "\"loginType\": \"ServiceApp\"");
        assertContains(attemptExchange.responseText(), "\"appId\": \"ww-login-suite\"");
        assertNotContains(attemptExchange.responseText(), "agentId");
        assertContains(attemptExchange.responseText(), "\"redirectUri\": \"http://localhost:8099/\"");
        assertNotContains(attemptExchange.responseText(), "secret");
        String loginState = attempt.get("state").getAsString();

        FakeHttpExchange loginExchange = new FakeHttpExchange("POST", "/api/v1/wecom/login/exchange");
        loginExchange.requestBodyJson("{\"code\":\"code-1\",\"state\":\"" + loginState + "\"}");
        App.routeForTests(loginExchange, config, store, viewer, loginAttempts);
        assertEquals(200, loginExchange.responseCode);
        assertContains(loginExchange.responseText(), "\"wecomUserId\": \"user-1\"");
        String viewerAuthToken = JsonParser.parseString(loginExchange.responseText()).getAsJsonObject()
                .get("viewerAuthToken").getAsString();

        FakeHttpExchange authenticatedConfig = new FakeHttpExchange(
                "GET", "/api/v1/wecom/js-sdk-config?url=http%3A%2F%2Flocalhost%3A8099%2F");
        authenticatedConfig.getRequestHeaders().set("X-WeCom-Viewer-Auth", viewerAuthToken);
        App.routeForTests(authenticatedConfig, config, store, viewer);
        assertEquals(200, authenticatedConfig.responseCode);
        assertContains(authenticatedConfig.responseText(), "\"corpId\": \"ww-test-corp\"");
        assertContains(authenticatedConfig.responseText(), "wwapp.invokeJsApiByCallInfo");
        assertNotContains(authenticatedConfig.responseText(), "permanent-code");

        FakeHttpExchange replayedLogin = new FakeHttpExchange("POST", "/api/v1/wecom/login/exchange");
        replayedLogin.requestBodyJson("{\"code\":\"code-2\",\"state\":\"" + loginState + "\"}");
        App.routeForTests(replayedLogin, config, store, viewer, loginAttempts);
        assertEquals(403, replayedLogin.responseCode);
        assertContains(replayedLogin.responseText(), "\"code\": \"FORBIDDEN\"");

        FakeHttpExchange missingStateLogin = new FakeHttpExchange("POST", "/api/v1/wecom/login/exchange");
        missingStateLogin.requestBodyJson("{\"code\":\"code-3\"}");
        App.routeForTests(missingStateLogin, config, store, viewer, loginAttempts);
        assertEquals(403, missingStateLogin.responseCode);

        FakeHttpExchange secretAttempt = new FakeHttpExchange("POST", "/api/v1/wecom/login/attempts");
        secretAttempt.requestBodyJson("{\"secret\":\"must-not-be-accepted\"}");
        App.routeForTests(secretAttempt, config, store, viewer, loginAttempts);
        assertEquals(400, secretAttempt.responseCode);

        AtomicInteger syncCalls = new AtomicInteger();
        WeComChatDataSyncService syncService = WeComChatDataSyncService.forTests(config,
                (installation, cursor, limit, timeout) -> {
                    syncCalls.incrementAndGet();
                    return new WeComChatDataGateway.ProgramPage(false, "cursor-1", List.of());
                },
                (version, encrypted) -> "unused",
                new WeComChatDataSyncService.StoreAccess() {
                    @Override public String cursor(WeComChatDataStore.SyncKey key) {
                        return "";
                    }

                    @Override public WeComChatDataStore.PublishResult publish(
                            WeComChatDataStore.SyncKey key, String nextCursor,
                            List<WeComChatDataStore.DecryptedMessage> messages) {
                        return new WeComChatDataStore.PublishResult(0, 0);
                    }
                },
                (action, result, userId) -> { });
        FakeHttpExchange initialSyncExchange = new FakeHttpExchange(
                "POST", "/api/v1/wecom/conversation-view/sync");
        initialSyncExchange.getRequestHeaders().set("X-WeCom-Viewer-Auth", viewerAuthToken);
        initialSyncExchange.requestBodyJson("{}");
        App.routeForTests(initialSyncExchange, config, store, viewer, loginAttempts, syncService);
        assertEquals(200, initialSyncExchange.responseCode);
        assertContains(initialSyncExchange.responseText(), "\"pages\": 1");
        assertContains(initialSyncExchange.responseText(), "\"stored\": 0");

        FakeHttpExchange missingSyncExchange = new FakeHttpExchange(
                "POST", "/api/v1/wecom/conversation-view/sessions");
        missingSyncExchange.requestBodyJson("{\"contactPointId\":\"wecom:ext-1\","
                + "\"viewerAuthToken\":\"" + viewerAuthToken + "\"}");
        App.routeForTests(missingSyncExchange, config, store, viewer, loginAttempts);
        assertEquals(200, missingSyncExchange.responseCode);
        assertEquals(1, syncCalls.get());

        FakeHttpExchange createExchange = new FakeHttpExchange("POST", "/api/v1/wecom/conversation-view/sessions");
        createExchange.requestBodyJson("{\"contactPointId\":\"wecom:ext-1\",\"viewerAuthToken\":\"" + viewerAuthToken + "\"}");
        App.routeForTests(createExchange, config, store, viewer, loginAttempts, syncService);
        assertEquals(200, createExchange.responseCode);
        assertEquals(1, syncCalls.get());
        String sessionId = JsonParser.parseString(createExchange.responseText()).getAsJsonObject()
                .get("viewerSessionId").getAsString();

        FakeHttpExchange detailExchange = new FakeHttpExchange(
                "GET", "/api/v1/wecom/conversation-view/sessions/" + sessionId);
        detailExchange.getRequestHeaders().set("X-WeCom-Viewer-Auth", viewerAuthToken);
        App.routeForTests(detailExchange, config, store, viewer);
        assertEquals(200, detailExchange.responseCode);
        assertContains(detailExchange.responseText(), "\"msgid\": \"m1\"");
        assertContains(detailExchange.responseText(), "\"secretKey\": \"s1\"");

        FakeHttpExchange eventWithSecret = new FakeHttpExchange(
                "POST", "/api/v1/wecom/conversation-view/events");
        eventWithSecret.requestBodyJson("{\"eventType\":\"component_error\","
                + "\"viewerSessionId\":\"" + sessionId + "\",\"secretKey\":\"must-not-be-accepted\"}");
        eventWithSecret.getRequestHeaders().set("X-WeCom-Viewer-Auth", viewerAuthToken);
        App.routeForTests(eventWithSecret, config, store, viewer);
        assertEquals(400, eventWithSecret.responseCode);
        assertContains(eventWithSecret.responseText(), "\"code\": \"INVALID_REQUEST\"");

        FakeHttpExchange componentErrorExchange = new FakeHttpExchange(
                "POST", "/api/v1/wecom/conversation-view/events");
        componentErrorExchange.requestBodyJson("{\"eventType\":\"component_error\","
                + "\"viewerSessionId\":\"" + sessionId + "\"}");
        componentErrorExchange.getRequestHeaders().set("X-WeCom-Viewer-Auth", viewerAuthToken);
        App.routeForTests(componentErrorExchange, config, store, viewer);
        assertEquals(202, componentErrorExchange.responseCode);

        FakeHttpExchange replayedComponentError = new FakeHttpExchange(
                "POST", "/api/v1/wecom/conversation-view/events");
        replayedComponentError.requestBodyJson("{\"eventType\":\"component_error\","
                + "\"viewerSessionId\":\"" + sessionId + "\"}");
        replayedComponentError.getRequestHeaders().set("X-WeCom-Viewer-Auth", viewerAuthToken);
        App.routeForTests(replayedComponentError, config, store, viewer);
        assertEquals(403, replayedComponentError.responseCode);

        FakeHttpExchange otherOwnerExchange = new FakeHttpExchange("POST", "/api/v1/wecom/conversation-view/sessions");
        otherOwnerExchange.requestBodyJson("{\"contactPointId\":\"wecom:ext-2\",\"viewerAuthToken\":\"" + viewerAuthToken + "\"}");
        App.routeForTests(otherOwnerExchange, config, store, viewer, loginAttempts, syncService);
        assertEquals(403, otherOwnerExchange.responseCode);
        assertContains(otherOwnerExchange.responseText(), "\"code\": \"FORBIDDEN\"");

        FakeHttpExchange missingPointExchange = new FakeHttpExchange("POST", "/api/v1/wecom/conversation-view/sessions");
        missingPointExchange.requestBodyJson("{\"contactPointId\":\"wecom:ext-missing\",\"viewerAuthToken\":\"" + viewerAuthToken + "\"}");
        App.routeForTests(missingPointExchange, config, store, viewer);
        assertEquals(403, missingPointExchange.responseCode);
        assertContains(missingPointExchange.responseText(), "\"code\": \"FORBIDDEN\"");
        assertContains(Files.readString(config.wecomViewerAuditFile(), StandardCharsets.UTF_8),
                "wecom.viewer.access_check");

        FakeHttpExchange oversizedLogin = new FakeHttpExchange("POST", "/api/v1/wecom/login/exchange");
        oversizedLogin.requestBodyJson("{\"code\":\"" + "x".repeat(4097) + "\"}");
        App.routeForTests(oversizedLogin, config, store, viewer);
        assertEquals(400, oversizedLogin.responseCode);
        assertContains(oversizedLogin.responseText(), "\"code\": \"INVALID_REQUEST\"");
    }

    private static void wecomSimulationRoutesAreNotExposedAtRuntime() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-token-route-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        UnifiedMessageStore store = new UnifiedMessageStore(config);
        WeComViewerService viewer = WeComViewerService.forTests(
                config,
                Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC),
                () -> "nonce",
                new WeComViewerService.StaticGateway("corp-ticket", "agent-ticket", "user-1"));
        for (String route : List.of(
                "/webhook/wecom",
                "/api/wecom/sync_msg", "/api/v1/wecom/sync_msg",
                "/api/wecom/messages", "/api/v1/wecom/messages",
                "/api/wecom/gettoken", "/api/v1/wecom/gettoken",
                "/api/wecom/login/attempts")) {
            String method = route.endsWith("gettoken") ? "GET" : "POST";
            FakeHttpExchange exchange = new FakeHttpExchange(method, route);
            if ("POST".equals(method)) exchange.requestBodyJson("{}");

            App.routeForTests(exchange, config, store, viewer);

            assertEquals(404, exchange.responseCode);
        }
        assertFalse(Files.exists(config.wecomDataFile()), "simulation routes must not write WECOM_DATA_FILE");
    }

    private static void exposesWecomAdapterAsAvailableViewerChannel() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        ChannelCapability capability = store.channelCapability("wecom");

        assertEquals("wecom", capability.channel);
        assertEquals("true", Boolean.toString(capability.available));
    }

    private static void chatAppStatusRecordsUpdateOriginalMessageInsteadOfCreatingMessages() throws Exception {
        Path dir = Files.createTempDirectory("message-center-status-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), ""
                + "{\"id\":\"chat-1\",\"direction\":\"outbound\",\"timestamp\":\"2026-07-10T01:00:00Z\",\"from\":\"8613266259485\",\"to\":\"8613800000000\",\"text\":\"hello\",\"raw\":\"{}\"}\n"
                + "{\"id\":\"chat-1-status\",\"direction\":\"status\",\"timestamp\":\"2026-07-10T01:01:00Z\",\"from\":\"8613266259485\",\"to\":\"8613800000000\",\"text\":\"Status: Read\",\"raw\":\"{}\"}\n",
                StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        List<UnifiedMessage> thread = store.thread("chatapp:whatsapp:8613800000000");
        assertEquals("1", Integer.toString(thread.size()));
        assertEquals("Read", thread.get(0).status);
        assertEquals("1", Integer.toString(store.contacts().size()));
    }

    private static void chatAppMediaJsonMessagesRenderCaptionAndAttachment() throws Exception {
        Path dir = Files.createTempDirectory("message-center-media-json-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-media-1\","
                + "\"direction\":\"outbound\","
                + "\"timestamp\":\"2026-07-10T01:00:00Z\","
                + "\"from\":\"8613266259485\","
                + "\"to\":\"8613800000000\","
                + "\"text\":\"{\\\"caption\\\":\\\"这是我们的系统\\\",\\\"mediaType\\\":\\\"image\\\",\\\"url\\\":\\\"https://example.com/a.png\\\"}\","
                + "\"raw\":\"{}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        List<UnifiedMessage> thread = store.thread("chatapp:whatsapp:8613800000000");
        assertEquals("", thread.get(0).title);
        assertEquals("这是我们的系统", thread.get(0).text);
        assertEquals("image", thread.get(0).mediaType);
        assertEquals("https://example.com/a.png", thread.get(0).mediaUrl);
        assertNotContains(thread.get(0).text, "\"caption\"");
    }

    private static void chatAppMediaPlaceholderTextShowsCaptionWithoutImagePrefix() throws Exception {
        Path dir = Files.createTempDirectory("message-center-media-placeholder-caption-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-media-placeholder-caption\","
                + "\"direction\":\"outbound\","
                + "\"timestamp\":\"2026-07-10T01:00:00Z\","
                + "\"from\":\"8613266259485\","
                + "\"to\":\"8613800000000\","
                + "\"text\":\"[image] 这是我们的营业执照\","
                + "\"mediaType\":\"image\","
                + "\"mediaUrl\":\"https://oss.example.com/license.png\","
                + "\"raw\":\"{}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessage message = testStore(dir, emailData, chatData.resolve("messages.jsonl"))
                .thread("chatapp:whatsapp:8613800000000").get(0);

        assertEquals("这是我们的营业执照", message.text);
        assertEquals("https://oss.example.com/license.png", message.mediaUrl);
    }

    private static void chatAppMediaMessagesExposeProxyMetadata() throws Exception {
        Path dir = Files.createTempDirectory("message-center-media-proxy-metadata-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-media-2\","
                + "\"direction\":\"inbound\","
                + "\"timestamp\":\"2026-07-10T01:00:00Z\","
                + "\"from\":\"8613800000000\","
                + "\"to\":\"8613266259485\","
                + "\"text\":\"{\\\"caption\\\":\\\"带附件\\\",\\\"mediaType\\\":\\\"image\\\",\\\"link\\\":\\\"https://oss.example.com/file.png\\\",\\\"objectKey\\\":\\\"cams/file.png\\\",\\\"mimeType\\\":\\\"image/png\\\",\\\"fileName\\\":\\\"file.png\\\"}\","
                + "\"mediaUrl\":\"https://oss.example.com/file.png\","
                + "\"objectKey\":\"cams/file.png\","
                + "\"mimeType\":\"image/png\","
                + "\"fileName\":\"file.png\","
                + "\"raw\":\"{\\\"objectKey\\\":\\\"cams/raw-file.png\\\",\\\"mimeType\\\":\\\"image/png\\\"}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        UnifiedMessage message = store.thread("chatapp:whatsapp:8613800000000").get(0);
        assertEquals("cams/file.png", message.objectKey);
        assertEquals("image/png", message.mimeType);
        assertEquals("file.png", message.fileName);
    }

    private static void chatAppMediaMessagesExtractUrlFromNestedRawMessageJson() throws Exception {
        Path dir = Files.createTempDirectory("message-center-media-nested-raw-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-media-nested-raw\","
                + "\"direction\":\"outbound\","
                + "\"timestamp\":\"2026-07-10T08:28:34Z\","
                + "\"from\":\"8613266259485\","
                + "\"to\":\"8613428277520\","
                + "\"text\":\"[image]\","
                + "\"mediaType\":\"image\","
                + "\"raw\":\"{\\\"messageTypeName\\\":\\\"image\\\",\\\"message\\\":\\\"{\\\\\\\"caption\\\\\\\":\\\\\\\"营业执照\\\\\\\",\\\\\\\"mediaType\\\\\\\":\\\\\\\"image\\\\\\\",\\\\\\\"url\\\\\\\":\\\\\\\"https://oss.example.com/nested.jpg\\\\\\\",\\\\\\\"mimeType\\\\\\\":\\\\\\\"image/jpeg\\\\\\\",\\\\\\\"fileName\\\\\\\":\\\\\\\"nested.jpg\\\\\\\"}\\\"}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessage message = testStore(dir, emailData, chatData.resolve("messages.jsonl"))
                .thread("chatapp:whatsapp:8613428277520").get(0);

        assertEquals("营业执照", message.text);
        assertEquals("https://oss.example.com/nested.jpg", message.mediaUrl);
        assertEquals("image/jpeg", message.mimeType);
        assertEquals("nested.jpg", message.fileName);
    }

    private static void mediaGatewayBuildsFreshOssSignedUrlFromStoredObjectKey() throws Exception {
        UnifiedMessage message = new UnifiedMessage();
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/old/file.png"
                + "?OSSAccessKeyId=STS.expired&Expires=1800000000&Signature=old";
        message.objectKey = "cams/file.png";
        Config config = new Config(Map.of(
                "ALIYUN_ACCESS_KEY_ID", "akid",
                "ALIYUN_ACCESS_KEY_SECRET", "secret"
        ));

        String signed = MediaGateway.signedOssUrl(config, message, 1800000000L);

        assertContains(signed, "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/cams/file.png?");
        assertContains(signed, "OSSAccessKeyId=akid");
        assertContains(signed, "Expires=1800000000");
        assertContains(signed, "Signature=uEvbSub8UDbDVmppyeQWAQp0xHA%3D");
        assertNotContains(signed, "STS.expired");
    }

    private static void mediaGatewayPrefersCamsPresignedUrlBeforeOssUrl() throws Exception {
        Path dir = Files.createTempDirectory("message-center-cams-presigned-media-test");
        AtomicReference<String> requestedFilePath = new AtomicReference<>("");
        AtomicReference<String> downloadedUrl = new AtomicReference<>("");
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:cams-media-1";
        message.mediaType = "image";
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/expired.jpg?OSSAccessKeyId=STS.old";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", dir.resolve("media-cache").toString()
        )), url -> {
            downloadedUrl.set(url);
            return new MediaGateway.MediaResponse("cached".getBytes(StandardCharsets.UTF_8), "image/jpeg", "fresh.jpg");
        }, filePath -> {
            requestedFilePath.set(filePath);
            return "https://cams.example.com/fresh-download.jpg";
        });

        gateway.fetch(message);

        assertEquals("100003592550/expired.jpg", requestedFilePath.get());
        assertEquals("https://cams.example.com/fresh-download.jpg", downloadedUrl.get());
    }

    private static void mediaGatewayNormalizesProtocolRelativePresignedUrl() throws Exception {
        Path dir = Files.createTempDirectory("message-center-cams-relative-url-test");
        AtomicReference<String> downloadedUrl = new AtomicReference<>("");
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:cams-relative-media";
        message.mediaType = "image";
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/expired.jpg";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", dir.resolve("media-cache").toString()
        )), url -> {
            downloadedUrl.set(url);
            return new MediaGateway.MediaResponse("cached".getBytes(StandardCharsets.UTF_8), "image/jpeg", "fresh.jpg");
        }, filePath -> "//bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/fresh.jpg");

        gateway.fetch(message);

        assertEquals("https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/fresh.jpg",
                downloadedUrl.get());
    }

    private static void mediaGatewayTriesFreshSignedOssUrlBeforeExpiredMediaUrlWhenCamsFails() throws Exception {
        Path dir = Files.createTempDirectory("message-center-signed-before-expired-media-test");
        List<String> attempts = new ArrayList<>();
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:signed-before-expired";
        message.mediaType = "image";
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/expired.jpg"
                + "?OSSAccessKeyId=STS.old&Expires=1&Signature=old";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", dir.resolve("media-cache").toString(),
                "ALIYUN_ACCESS_KEY_ID", "akid",
                "ALIYUN_ACCESS_KEY_SECRET", "secret"
        )), url -> {
            attempts.add(url);
            if (url.contains("OSSAccessKeyId=akid")) {
                return new MediaGateway.MediaResponse("fresh".getBytes(StandardCharsets.UTF_8), "image/jpeg", "fresh.jpg");
            }
            throw new MediaGateway.MediaUnavailableException("expired");
        }, filePath -> {
            throw new IOException("CAMS timeout");
        });

        gateway.fetch(message);

        assertEquals("1", Integer.toString(attempts.size()));
        assertContains(attempts.get(0), "OSSAccessKeyId=akid");
        assertNotContains(attempts.get(0), "OSSAccessKeyId=STS.old");
    }

    private static void mediaGatewaySkipsSlowCamsPresignAndFallsBackToFreshSignedOssUrl() throws Exception {
        Path dir = Files.createTempDirectory("message-center-slow-cams-media-test");
        AtomicReference<String> downloadedUrl = new AtomicReference<>("");
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:slow-cams-media";
        message.mediaType = "image";
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/slow.jpg"
                + "?OSSAccessKeyId=STS.old&Expires=1&Signature=old";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", dir.resolve("media-cache").toString(),
                "ALIYUN_ACCESS_KEY_ID", "akid",
                "ALIYUN_ACCESS_KEY_SECRET", "secret",
                "CHATAPP_MEDIA_PRESIGNED_TIMEOUT_SECONDS", "1"
        )), url -> {
            downloadedUrl.set(url);
            return new MediaGateway.MediaResponse("fresh".getBytes(StandardCharsets.UTF_8), "image/jpeg", "fresh.jpg");
        }, filePath -> {
            Thread.sleep(3000);
            return "https://cams.example.com/slow-download.jpg";
        });

        long started = System.nanoTime();
        gateway.fetch(message);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 2000, "slow CAMS presign blocked media fetch for " + elapsedMillis + "ms");
        assertContains(downloadedUrl.get(), "OSSAccessKeyId=akid");
    }

    private static void mediaGatewaySummarizesOssXmlErrorCode() {
        String message = MediaGateway.unavailableMessage(403,
                "<Error><Code>AccessDenied</Code><Message>Access denied.</Message><RequestId>abc</RequestId></Error>");

        assertContains(message, "OSS 返回 HTTP 403");
        assertContains(message, "AccessDenied");
        assertNotContains(message, "RequestId");
    }

    private static void mediaGatewayReportsCamsAndFallbackDownloadFailures() throws Exception {
        Path dir = Files.createTempDirectory("message-center-cams-failure-summary-test");
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:cams-media-fail";
        message.mediaType = "image";
        message.mediaUrl = "https://bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/expired.jpg?OSSAccessKeyId=STS.old&Signature=secret";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", dir.resolve("media-cache").toString()
        )), url -> {
            throw new MediaGateway.MediaUnavailableException("OSS 返回 HTTP 403");
        }, filePath -> {
            throw new IOException("No permission for filePath " + filePath);
        });

        try {
            gateway.fetch(message);
            throw new AssertionError("Expected media fetch to fail");
        } catch (MediaGateway.MediaUnavailableException ex) {
            assertContains(ex.getMessage(), "CAMS GeneratePresignedUrl 失败：No permission for filePath 100003592550/expired.jpg");
            assertContains(ex.getMessage(), "bucket-chatapp-file-internal.oss-ap-southeast-1.aliyuncs.com/100003592550/expired.jpg");
            assertContains(ex.getMessage(), "OSS 返回 HTTP 403");
            assertNotContains(ex.getMessage(), "OSSAccessKeyId=STS.old");
            assertNotContains(ex.getMessage(), "Signature=secret");
        }
    }

    private static void mediaGatewayCachesDownloadedMediaLocally() throws Exception {
        Path dir = Files.createTempDirectory("message-center-media-cache-test");
        Path cacheDir = dir.resolve("media-cache");
        AtomicInteger hits = new AtomicInteger();
        UnifiedMessage message = new UnifiedMessage();
        message.id = "chatapp:media-cache-1";
        message.mediaType = "image";
        message.mimeType = "image/png";
        message.fileName = "photo.png";
        message.mediaUrl = "https://oss.example.com/photo.png";
        MediaGateway gateway = new MediaGateway(new Config(Map.of(
                "MEDIA_CACHE_DIR", cacheDir.toString(),
                "MEDIA_MAX_BYTES", "1024"
        )), url -> {
            hits.incrementAndGet();
            return new MediaGateway.MediaResponse("image-bytes".getBytes(StandardCharsets.UTF_8),
                    "image/png", "photo.png");
        });

        MediaGateway.MediaResponse first = gateway.fetch(message);
        MediaGateway.MediaResponse second = gateway.fetch(message);

        assertEquals("image-bytes", new String(first.bytes, StandardCharsets.UTF_8));
        assertEquals("image-bytes", new String(second.bytes, StandardCharsets.UTF_8));
        assertEquals("1", Integer.toString(hits.get()));
        try (Stream<Path> files = Files.list(cacheDir)) {
            assertEquals("1", Long.toString(files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".type"))
                    .count()));
        }
    }

    private static void rendersChatAppTemplateMessagesFromTemplateCache() throws Exception {
        Path dir = Files.createTempDirectory("message-center-template-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(dir.resolve("templates.json"), "[{\"templateCode\":\"tpl-1\",\"templateName\":\"wl\",\"languageCode\":\"zh_CN\",\"body\":\"Hello $(text), address $(text1), contact $(text2)\"}]", StandardCharsets.UTF_8);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-template-1\","
                + "\"direction\":\"outbound\","
                + "\"timestamp\":\"2026-07-10T01:00:00Z\","
                + "\"from\":\"8613266259485\","
                + "\"to\":\"8613800000000\","
                + "\"text\":\"\","
                + "\"raw\":\"{\\\"messageType\\\":\\\"TEMPLATE\\\",\\\"templateCode\\\":\\\"tpl-1\\\",\\\"languageCode\\\":\\\"zh_CN\\\",\\\"message\\\":\\\"{\\\\\\\"text\\\\\\\":\\\\\\\"Eva\\\\\\\",\\\\\\\"text1\\\\\\\":\\\\\\\"Shenzhen\\\\\\\",\\\\\\\"text2\\\\\\\":\\\\\\\"Support\\\\\\\"}\\\"}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        List<UnifiedMessage> thread = store.thread("chatapp:whatsapp:8613800000000");
        assertEquals("", thread.get(0).title);
        assertEquals("Hello Eva, address Shenzhen, contact Support", thread.get(0).text);
    }

    private static void rendersWebShellWithChineseCopyAndUnifiedSendActions() {
        String html = App.pageHtml();

        assertContains(html, "统一消息中心");
        assertContains(html, "发送邮件");
        assertContains(html, "发送 WhatsApp");
        assertContains(html, "收取邮件");
        assertContains(html, "同步 WhatsApp");
        assertContains(html, "同步企业微信会话");
        assertContains(html, "刷新 ${result.updated || 0}");
        assertContains(html, "附件 ${result.mediaCached || 0}");
        assertContains(html, "排队 ${result.mediaQueued || 0}");
        assertContains(html, "失败 ${result.mediaFailed || 0}");
        assertContains(html, "页 ${result.pages || 0}");
        assertContains(html, "耗时 ${formatDuration(result.durationMillis)}");
        assertContains(html, "function formatDuration");
        assertContains(html, "result.mediaFailures?.[0]?.reason");
        assertContains(html, "/api/sync/email");
        assertContains(html, "/api/sync/chatapp");
        assertContains(html, "/api/v1/wecom/conversation-view/sync");
        assertContains(html, "workspace-topbar");
        assertContains(html, "contact-points-line");
        assertContains(html, "sync-actions");
        assertContains(html, "utility-actions");
        assertContains(html, "aria-label=\"刷新\"");
        assertContains(html, "detailToggleBtn");
        assertContains(html, "contactDetailPanel");
        assertContains(html, "account-list");
        assertContains(html, "account-actions");
        assertContains(html, "account-split-button");
        assertContains(html, "data-split-account");
        assertContains(html, "tool-icon split");
        assertContains(html, "/api/contact-groups/split");
        assertContains(html, "profile-readonly");
        assertContains(html, "profile-readonly-title\">联系人资料");
        assertContains(html, "readonly-row");
        assertContains(html, "profile-tags-readonly");
        assertContains(html, "thread-title-row");
        assertContains(html, "threadTitleText");
        assertContains(html, "editProfileBtn");
        assertContains(html, "openProfileModal");
        assertContains(html, "profileModal");
        assertContains(html, "profileNicknameInput");
        assertContains(html, "profileTagsInput");
        assertContains(html, "profileSaveBtn");
        assertContains(html, "accountSelectHtml");
        assertContains(html, "bindAccountSelect");
        assertContains(html, "data-account-select");
        assertContains(html, "unreadByContact");
        assertContains(html, "contact-unread-dot");
        assertContains(html, "contact-unread-count");
        assertContains(html, "/api/media?id=");
        assertContains(html, "mediaPreviewHtml(m)");
        assertContains(html, "msg-media-preview");
        assertContains(html, "msg-media-loading");
        assertContains(html, "正在拉取图片");
        assertContains(html, "mediaLoaded(this)");
        assertContains(html, "mediaFailed(this)");
        assertContains(html, "previewImageModal");
        assertContains(html, "previewImageEl");
        assertContains(html, "openImagePreview(event");
        assertContains(html, "closeImagePreview");
        assertContains(html, "data-preview-image");
        assertContains(html, "object-fit:cover");
        assertContains(html, "cursor:zoom-in");
        assertContains(html, "image-preview-backdrop");
        assertContains(html, "image-preview-frame");
        assertContains(html, "msg-media-unavailable");
        assertContains(html, "附件暂不可用");
        assertContains(html, "data-open-media");
        assertContains(html, "openAttachment");
        assertContains(html, "URL.createObjectURL");
        assertContains(html, "附件打开失败");
        assertContains(html, "media-open-link");
        assertContains(html, "data-unread");
        assertContains(html, "reconcileUnreadContacts");
        assertContains(html, "isUnreadSource");
        assertContains(html, "clearContactUnread");
        assertContains(html, "c.id !== state.selectedPointId");
        assertContains(html, "aria-label=\"有未读消息\"");
		assertContains(html, "state.selectedMessageId || state.selectedCallRecordId || profileModalOpen() || detailEditing()");
        assertContains(html, "async function refreshSelectedContactViews");
        assertContains(html, "await refreshSelectedContactViews(false);");
        assertContains(html, "toast('联系人已合并')");
        assertContains(html, "scrollbar-color:transparent transparent");
        assertContains(html, "scrollbar-gutter:stable");
        assertContains(html, "scrollbar-width:thin");
        assertContains(html, "::-webkit-scrollbar { width:6px; height:6px; }");
        assertContains(html, "::-webkit-scrollbar-thumb { background:transparent");
        assertContains(html, ".contact-list.scrolling");
        assertContains(html, ".thread.scrolling");
        assertContains(html, "bindScrollSurfaces");
        assertContains(html, "markScrollSurfaceScrolling");
        assertContains(html, "sendPointForChannel");
        assertContains(html, "selectedPointByChannel");
        assertContains(html, "profileDirty");
        assertContains(html, "markProfileDirty");
        assertContains(html, "profileSaveState");
        assertContains(html, "/api/contact-groups/profile");
        assertContains(html, "detail-collapsed");
        assertContains(html, "transition:grid-template-columns");
        assertContains(html, "contactsRenderKey");
        assertContains(html, "threadRenderKeyByContact");
        assertContains(html, "if (silent && key === state.contactsRenderKey)");
        assertContains(html, "if (keepScroll && key === state.threadRenderKeyByContact[id])");
        assertContains(html, "if (silent && isUserScrolling())");
        assertContains(html, "state.pendingSilentRefresh = true");
        assertContains(html, "finishScrollSurfaceScrolling");
        assertContains(html, "message-row");
        assertContains(html, "msg-avatar");
        assertContains(html, "avatar-icon");
        assertContains(html, "align-items:start");
        assertContains(html, ".msg { width:fit-content; max-width:100%; min-width:0;");
        assertContains(html, ".msg.has-media { width:min(320px, 100%); }");
        assertContains(html, "hasMedia(m) ? 'has-media' : ''");
        assertContains(html, "msg-meta-line");
        assertContains(html, "status-icon");
        assertContains(html, "composer compact");
        assertContains(html, "height:260px");
        assertContains(html, "composer-tabs");
        assertContains(html, "aspect-ratio:var(--media-ratio, 4 / 3)");
        assertContains(html, "msg-media media-visual");
        assertContains(html, "detailEditing()");
        assertContains(html, "composer-editor");
        assertContains(html, "composer-toolbar");
        assertContains(html, "tool-icon");
        assertTrue(emojiSetSize(html) >= 48, "emoji picker should expose a practical emoji set");
        assertContains(html, "'😂'");
        assertContains(html, "'👌'");
        assertContains(html, "'🎉'");
        assertContains(html, "'🧾'");
        assertContains(html, "message-detail-standalone");
        assertContains(html, "$('detail').innerHTML = `<div class=\"message-detail-panel message-detail-standalone\"");
        assertContains(html, ".contact { position:relative; display:grid; grid-template-columns:38px 1fr; gap:9px; padding:8px 12px; cursor:pointer; min-height:58px;");
        assertContains(html, ".recipient-control { display:block; }");
        assertContains(html, "appearance:none");
        assertNotContains(html, ".contact { display:grid; grid-template-columns:42px 1fr; gap:10px; padding:12px; border-bottom:1px solid #edf0f5; cursor:pointer; }");
        assertNotContains(html, "data-account-input");
        assertNotContains(html, "grid-template-columns:minmax(118px, .42fr) minmax(0, 1fr)");
        assertNotContains(html, "const target = $('messageDetailPanel')");
        assertNotContains(html, "点击一条消息查看完整内容");
        assertNotContains(html, "<div class=\"brand\">联系人资料</div>");
        assertNotContains(html, "账号、昵称和标签");
        assertNotContains(html, "点击联系人查看融合账号");
        assertNotContains(html, "data-use-account");
        assertNotContains(html, "selectSendPoint");
        assertNotContains(html, "当前发送");
        assertNotContains(html, "用于发送");
        assertNotContains(html, "mode-tabs");
        assertNotContains(html, "<input id=\"nicknameInput\"");
        assertNotContains(html, "<input id=\"tagsInput\"");
        assertNotContains(html, "id=\"saveProfileBtn\"");
        assertNotContains(html, "确认保存");
        assertNotContains(html, ".msg { width:100%;");
        assertNotContains(html, "href=\"${esc(m.mediaUrl)}\"");
        assertNotContains(html, "target=\"_blank\" rel=\"noreferrer\">打开附件</a>");
        assertNotContains(html, "::-webkit-scrollbar { width:0; height:0; }");
        assertNotContains(html, "企业微信 API 接入位已预留");
        assertNotContains(html, "缁熶竴");
        assertNotContains(html, "閭欢");
        assertNotContains(html, "宸插彂");
    }

    private static void frontendAddsWeComViewerPanelWithoutReplacingExistingInteractions() {
        String html = App.pageHtml();

        assertContains(html, "id=\"wecomLoginScreen\"");
        assertContains(html, "id=\"wwLoginPanel\"");
        assertContains(html, "id=\"shell\" hidden");
        assertContains(html, "function initWeComLogin()");
        assertContains(html, "ww.createWWLoginPanel({");
        assertContains(html, "login_type: attempt.loginType");
        assertContains(html, "appid: attempt.appId");
        assertNotContains(html, "login_type: 'CorpApp'");
        assertNotContains(html, "agentid: attempt.agentId");
        assertContains(html, "redirect_type: 'callback'");
        assertContains(html, "onLoginSuccess({ code })");
        assertContains(html, "/api/v1/wecom/login/attempts");
        assertContains(html, "/api/v1/wecom/login/exchange");
        assertContains(html, "function currentWeComAuth()");
        assertContains(html, "function renderWeComViewerPanel(contact)");
        assertContains(html, "function loadWeComViewer(contact)");
        assertContains(html, "function ensureWeComViewerSdk(config)");
        assertContains(html, "function loadWeComSdk()");
        assertContains(html, "const WECOM_SDK_SRC = 'https://wwcdn.weixin.qq.com/node/open/js/wecom-jssdk-2.3.4.js'");
        assertContains(html, "const WECOM_JWXWORK_SRC = 'https://open.work.weixin.qq.com/wwopen/js/jwxwork-1.0.0.js'");
        assertContains(html, "const WECOM_LOGIN_EXPIRED_MARKERS = ['42006','42003','40029','Missing open sid']");
        assertNotContains(html, "<script src=\"https://wwcdn.weixin.qq.com/node/open/js/wecom-jssdk-2.3.4.js\"></script>");
        assertContains(html, "企业微信 JS-SDK 加载超时");
        assertContains(html, "weComSdkLoadPromise = null;");
        assertContains(html, "button.disabled = true;");
        assertContains(html, "正在同步企业微信会话");
        assertContains(html, "button.disabled = false;");
        assertContains(html, "data-wecom-segment-id");
        assertContains(html, "async function mountWeComTimelineMessages(detail, viewerAuthToken, contactPointId,");
        assertContains(html, "const WECOM_VIEWER_AUTO_REFRESH_MS = 60000;");
        assertContains(html, "function refreshWeComViewerIfDue()");
        assertContains(html, "renderContactDetail(contact);\n      await refreshWeComViewerIfDue();");
        assertContains(html, "wecom-segment-frame");
        assertContains(html, "const WECOM_RENDER_CONCURRENCY = 4;");
        assertContains(html, "const WECOM_VIEWPORT_COMMIT_MIN = 5;");
        assertContains(html, "const WECOM_VIEWPORT_COMMIT_MAX = 8;");
        assertContains(html, "const WECOM_ACTIVE_FRAME_LIMIT = 30;");
        assertContains(html, "async function runWeComRenderQueue(jobs, concurrency = WECOM_RENDER_CONCURRENCY,");
        assertContains(html, "function retryWeComSegment(segmentId)");
        assertContains(html, "handleMounted()");
        assertContains(html, "messageIds");
        assertNotContains(html, "wecom-message-toggle");
        assertNotContains(html, "function toggleWeComMessageFrame(frame, button)");
        assertNotContains(html, "max-height:88px");
        assertNotContains(html, "max-height:360px");
        assertContains(html, ".msg.wecom-message { width:fit-content;");
        assertContains(html, ".wecom-segment-host { display:inline-grid;");
        assertNotContains(html, ".wecom-segment-host.pending { display:none; }");
        assertNotContains(html, ".message-row:has(.wecom-segment-host.pending) { display:none; }");
        assertNotContains(html, "visibility:hidden; pointer-events:none; contain:layout style paint;");
        assertContains(html, ".message-row:has(.wecom-segment-host.pending) { position:fixed;");
        assertContains(html, ".wecom-contact-window { position:fixed; left:-100000px;");
        assertContains(html, "opacity:0; pointer-events:none; contain:layout style paint;");
        assertContains(html, ".wecom-segment-frame iframe { width:100%; height:100%;");
        assertContains(html, "wx:for=\"{{data.msgList}}\"");
        assertContains(html, "wx:key=\"msgid\"");
        assertContains(html, "message-id=\"{{item.msgid}}\"");
        assertContains(html, "secret-key=\"{{item.secretKey}}\"");
        assertContains(html, "open-type=\"viewMessage\"");
        assertContains(html, "const WECOM_EXPANDED_PREVIEW_LIMIT = 15;");
        assertContains(html, "const weComExpandedPreviews = new Map();");
        assertContains(html, "function weComPreviewHeight(modalSize)");
        assertContains(html, "function openWeComInlinePreview(host, contactPointId, messageId,");
        assertContains(html, "function collapseWeComInlinePreview(key)");
        assertContains(html, "openWeComInlinePreview(host, contactPointId, activeMessageId,");
        assertContains(html, "className = 'wecom-message-collapse'");
        assertContains(html, "setAttribute('aria-label', '收起企业微信消息')");
        assertNotContains(html, "openWeComModal({ modalUrl, modalSize });\n              return false;");
        assertContains(html, "class=\"wecom-segment-row {{item.direction}}\"");
        assertContains(html, "class=\"wecom-segment-bubble\"");
        assertContains(html, ".wecom-segment-row.inbound { justify-content:flex-start; }");
        assertContains(html, ".wecom-segment-row.outbound { justify-content:flex-end; }");
        assertContains(html, "direction:message?.direction === 'outbound' ? 'outbound' : 'inbound'");
        assertNotContains(html, "width:280px; height:40px;");
        assertNotContains(html, "width:min(360px,100%)");
        assertContains(html, "function cancelWeComRenderWork()");
        assertContains(html, "weComRenderQueue.splice(0).forEach(entry => entry.resolve({ status:'cancelled' }));");
        assertContains(html, "if (!automatic) resetWeComContactFrames(contact.id, root);");
        assertNotContains(html, "windowState.ready = true;\n        commitWeComContactWindow(id, windowState);");
        assertContains(html, "new Map((detail.messages || []).map(item => [item.msgid, item]))");
        assertContains(html, "data: { msgList:references }");
        assertNotContains(html, "hosts.forEach(host => {\n        let componentErrorReported = false;");
        assertNotContains(html, "id=\"wecomViewerContainer\"");
        assertNotContains(html, "style: `.msg { height: 100%; overflow: auto; }`");
        assertContains(html, "ww.register({");
        assertContains(html, "await ww.initOpenData();");
        assertContains(html, "ww.createOpenDataFrameFactory()");
        assertContains(html, "/api/v1/wecom/js-sdk-config");
        assertContains(html, "/api/v1/wecom/conversation-view/sessions");
        assertContains(html, "/api/v1/wecom/conversation-view/events");
        assertContains(html, "reportWeComViewerEvent");
        assertContains(html, "'wwapp.invokeJsApiByCallInfo'");
        assertContains(html, "binderror=\"handleSegmentMessageError\"");
        assertContains(html, "handleModal({ modalUrl, modalSize })");
        assertContains(html, "X-WeCom-Viewer-Auth");
        assertNotContains(html, "企业微信 API 接入位已预留");
        assertContains(html, "renderChatMode(state.selectedMode, point?.value || '');");
        assertContains(html, "if (state.selectedChannel === 'email')");
        assertContains(html, "if (state.selectedChannel === 'chatapp')");
        assertNotContains(html, "${ch==='wecom'?'disabled':''}");
        assertNotContains(html, "demo-local-code");
        assertNotContains(html, "?viewerAuthToken=");
        assertNotContains(html, "function wecomAuthCode()");
        assertNotContains(html, "new URLSearchParams(window.location.search).get('code')");
        assertNotContains(html, "localStorage");
        assertNotContains(html, "sessionStorage");
        assertNotContains(html, "init().catch(err => toast(err.message))");
        assertTrue(html.indexOf("initWeComLogin().catch") >= 0,
                "WeCom login must be the page bootstrap entry");
    }

    private static void frontendWeComSegmentContractIsPresent() {
        String html = App.pageHtml();

        assertContains(html, "const WECOM_SEGMENT_MESSAGE_LIMIT = 15;");
        assertContains(html, "const WECOM_MIXED_SEGMENT_MAX = 6;");
        assertContains(html, "function weComLayoutMode(contact)");
        assertContains(html, "function balancedWeComSegmentSizes(messageCount,");
        assertContains(html, "function standaloneWeComWindow(items,");
        assertContains(html, "function segmentTimelineItems(items,");
        assertContains(html, "data-wecom-segment-id");
        assertContains(html, "data-wecom-layout");
        assertContains(html, "wecom-message-row");
        assertContains(html, "wecom-standalone-row");
        assertContains(html, ".message-row.wecom-message-row { grid-template-columns:minmax(0,1fr);");
        assertContains(html, ".message-row.wecom-message-row .msg-avatar { display:none; }");
        assertContains(html, ".message-row.wecom-message-row .msg.wecom-message, .message-row.wecom-message-row .wecom-segment-host, .message-row.wecom-message-row .wecom-segment-frame { width:100%; max-width:none;");
        assertContains(html, "function weComSegmentFrameHeight(host, messageCount)");
        assertContains(html, "function weComViewerHasMountedSegments(viewer, root, contactPointId)");
        assertContains(html, "host?.dataset?.wecomLayout !== 'standalone'");
        assertNotContains(html, "display-type=\"text\"");
        assertNotContains(html, "ww-open-message {");
        assertNotContains(html, "data-wecom-message-id");
    }

    private static void rendersWebShellWithPagedThreadRequestContract() {
        String html = App.pageHtml();

        assertContains(html, "const THREAD_PAGE_SIZE = 15;");
		assertContains(html, "const page = await viewerApi(threadPageUrl(id));");
        assertContains(html, "const items = page.items || [];");
        assertContains(html, "nextCursor: page.nextCursor || null");
        assertContains(html, "threadRevision: page.threadRevision");
        assertContains(html, "threadLoadSeqByContact:{}");
        assertContains(html, "function nextThreadLoadSeq(id)");
        assertContains(html, "function currentThreadLoadSeq(id)");
        assertContains(html, "const requestSeq = nextThreadLoadSeq(id);");
        assertContains(html, "if (state.selectedPointId !== id || currentThreadLoadSeq(id) !== requestSeq) return;");
        assertContains(html, "const pageItemCount = Number(page.itemCount);");
        assertContains(html, "const threadRevision = String(page.threadRevision || '');");
		assertContains(html, "if (!Number.isFinite(pageItemCount) || !threadRevision) throw new Error('时间线页合同缺少版本信息');");
        assertNotContains(html, "contact?.messageCount");
        assertContains(html, "function threadPageUrl(id, cursor = '')");
		assertContains(html, "'/api/v1/contacts/' + encodeURIComponent(id) + '/timeline?limit=' + THREAD_PAGE_SIZE");
    }

    private static void apiThreadsRouteReturnsPagedObjectAndParsesCursorLimit() throws Exception {
        Path dir = Files.createTempDirectory("message-center-thread-route-test");
        Path emailData = dir.resolve("email");
        Path chatFile = dir.resolve("chatapp/messages.jsonl");
        Files.createDirectories(emailData);
        Files.createDirectories(chatFile.getParent());
        Config config = testConfig(dir, emailData, chatFile);
        RecordingThreadPageStore store = new RecordingThreadPageStore(config);
        FakeHttpExchange exchange = new FakeHttpExchange(
                "GET", "/api/threads?contactPointId=contact-1&limit=12&cursor=cursor-1");

        invokeRoute(exchange, config, store);

        assertEquals(200, exchange.responseCode);
        assertEquals("contact-1", store.contactPointId);
        assertEquals("cursor-1", store.cursor);
        assertEquals(12, store.limit);
        assertContains(exchange.responseText(), "\"items\"");
        assertContains(exchange.responseText(), "\"nextCursor\": \"older-cursor\"");
        assertContains(exchange.responseText(), "\"messageCount\": 1");
        assertContains(exchange.responseText(), "\"threadRevision\"");
        assertContains(exchange.responseText(), "\"text\": \"hello\"");

        FakeHttpExchange invalidLimit = new FakeHttpExchange(
                "GET", "/api/threads?contactPointId=contact-2&limit=bad");
        invokeRoute(invalidLimit, config, store);

        assertEquals(200, invalidLimit.responseCode);
        assertEquals("contact-2", store.contactPointId);
        assertEquals("", store.cursor);
        assertEquals(10, store.limit);
    }

    private static void rendersWebShellWithOlderThreadScrollLoader() {
        String html = App.pageHtml();

        assertContains(html, "loadOlderThreadMessages();");
        assertContains(html, "async function loadOlderThreadMessages()");
        assertContains(html, "if (!page || !page.nextCursor || page.isLoadingOlder) return;");
        assertContains(html, "const requestSeq = currentThreadLoadSeq(id);");
        assertContains(html, "const oldScrollHeight = threadEl.scrollHeight;");
        assertContains(html, "page.items = mergeThreadMessages([...(older.items || []), ...page.items]);");
        assertContains(html, "threadEl.scrollTop = threadEl.scrollHeight - oldScrollHeight + oldScrollTop;");
        assertContains(html, "if (state.selectedPointId !== id || state.threadPages[id] !== page || currentThreadLoadSeq(id) !== requestSeq) return;");
        assertContains(html, "const previousThreadRevision = existing ? existing.threadRevision || '' : '';");
        assertContains(html, "const shouldKeepLoadedThread = keepScroll && existing && existing.hasLoadedInitial && previousThreadRevision === threadRevision;");
        assertContains(html, "const merged = shouldKeepLoadedThread");
		assertContains(html, "pageItemCount,");
        assertContains(html, "threadRevision,");
        assertContains(html, "if (shouldKeepLoadedThread && existing.nextCursor) state.threadPages[id].nextCursor = existing.nextCursor;");
		assertContains(html, "if (shouldKeepLoadedThread && !existing.nextCursor && pageItemCount <= merged.length) state.threadPages[id].nextCursor = null;");
        assertContains(html, "if ((older.threadRevision || '') !== (page.threadRevision || '')) {");
        assertContains(html, "await loadThread(id, false);");
        assertContains(html, "el.onwheel = event => { if (event.deltaY < 0) loadOlderThreadMessages(); };");
        assertContains(html, "el.ontouchstart = event => { state.threadTouchY = event.touches?.[0]?.clientY || 0; };");
        assertContains(html, "el.ontouchmove = event => {");
        assertContains(html, "if (y > state.threadTouchY + 8) loadOlderThreadMessages();");
        assertContains(html, "function mergeThreadMessages(messages)");
        assertContains(html, "const seen = new Set();");
		assertContains(html, "const key = `${message.type || ''}:${message.sortId || ''}`;");
        assertContains(html, "if (!key) { merged.push(message); return; }");
        assertNotContains(html, "`${message.timestamp || ''}:${message.channel || ''}:${message.text || message.summary || ''}`");
        assertContains(html, "return merged;");
        assertNotContains(html, ".sort((a, b) =>");
        assertContains(html, "renderThreadMessages(contact, page.items);");
    }

    @Test
    static void frontendWeComColdContactSwitchKeepsOldPanelUntilReady() {
        String html = App.pageHtml();

        assertContains(html, "const weComContactWindows = new Map();");
        assertContains(html, "function prepareWeComContactWindow(contactPointId, detail, viewerAuthToken)");
        assertContains(html, "function commitWeComContactWindow(contactPointId, windowState)");
        assertContains(html, "weComRenderGeneration++;");
        assertContains(html, "windowState.committed");
        assertContains(html, "await prepareWeComContactWindow");
        assertNotContains(html, "weComTimelineViewer = null;\n        closeProfileModal();");
    }

    @Test
    void frontendWeComTimelineKeepsMountedHostAcrossRefreshAndHistoryInsert() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-stable-timeline-test");
        Path html = dir.resolve("page.html");
        Files.writeString(html, App.pageHtml(), StandardCharsets.UTF_8);
        Path probe = Path.of(getClass().getResource("/wecom-stable-timeline-probe.mjs").toURI());

        Process process = new ProcessBuilder("node", probe.toString(), html.toString())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("wecom stable timeline probe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new AssertionError("wecom stable timeline probe failed:\n" + output);
        }
    }

    @Test
    void frontendWeComViewerRefreshesImmediatelyForNewTimelineMessageIds() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-viewer-auto-refresh-test");
        Path html = dir.resolve("page.html");
        Files.writeString(html, App.pageHtml(), StandardCharsets.UTF_8);
        Path probe = Path.of(getClass().getResource("/wecom-viewer-auto-refresh-probe.mjs").toURI());

        Process process = new ProcessBuilder("node", probe.toString(), html.toString())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("wecom viewer auto-refresh probe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new AssertionError("wecom viewer auto-refresh probe failed:\n" + output);
        }
    }

    @Test
    void frontendWeComHistoryPageRequestsRenderedViewerBatchAndBoundsWindowLifetime() {
        String html = App.pageHtml();

        assertContains(html, "const historyWeComMessageIds = weComHistoryViewerMessageIds(contact, older.items || [], staging);");
        assertContains(html, "messageIds:historyWeComMessageIds");
        assertNotContains(html, "messageIds:olderWeComMessageIds");
        assertContains(html, "root:staging");
        assertContains(html, "mountWeComTimelineMessages(viewer.detail, login.viewerAuthToken");
        assertContains(html, "const WECOM_CONTACT_WINDOW_LIMIT = 3;");
        assertContains(html, "viewer.expiresAt");
        assertContains(html, "invalidateWeComContactWindow");
        assertContains(html, "retainRecentWeComFrames");
    }

    @Test
    void frontendWeComColdContactSwitchMovesPreparedDomAtomically() throws Exception {
        Path dir = Files.createTempDirectory("message-center-wecom-contact-window-test");
        Path html = dir.resolve("page.html");
        Files.writeString(html, App.pageHtml(), StandardCharsets.UTF_8);
        Path probe = Path.of(getClass().getResource("/wecom-contact-window-probe.mjs").toURI());

        Process process = new ProcessBuilder("node", probe.toString(), html.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("wecom contact window probe timed out");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("wecom contact window probe failed:\n" + output);
        }
    }

    @Test
    private static void frontendThreadPaginationBehaviorLoadsOlderPages() throws Exception {
        Path dir = Files.createTempDirectory("message-center-frontend-thread-pagination-test");
        Path html = dir.resolve("page.html");
        Path probe = dir.resolve("probe.mjs");
        Files.writeString(html, App.pageHtml(), StandardCharsets.UTF_8);
        Files.writeString(probe, frontendThreadPaginationProbe(), StandardCharsets.UTF_8);

        Process process = new ProcessBuilder("node", probe.toString(), html.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("frontend thread pagination probe timed out");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("frontend thread pagination probe failed:\n" + output);
        }
    }

    private static String frontendThreadPaginationProbe() {
        return """
                import assert from 'node:assert/strict';
                import fs from 'node:fs';
                import vm from 'node:vm';

                const html = fs.readFileSync(process.argv[2], 'utf8');
                let script = html.match(/<script>([\\s\\S]*?)<\\/script>/)?.[1];
                assert.ok(script, 'page must contain the embedded script');
                script = script.replace(/\\n\\s*init\\(\\)\\.catch\\(err => toast\\(err\\.message\\)\\);\\s*$/, '');
                script = script.replace(/\\n\\s*initWeComLogin\\(\\)\\.catch\\(showWeComLoginError\\);\\s*$/, '');

                const requests = [];
                const responses = [
                  page(11, 20, 'cursor-older', 'rev-1', 20),
                  page(1, 10, null, 'rev-1', 20),
                  page(1, 10, null, 'rev-stale', 20),
                  page(21, 30, 'cursor-fresh', 'rev-2', 30)
                ];
                const elements = new Map();

                function page(start, end, nextCursor, threadRevision, itemCount) {
                  const items = [];
                  for (let i = start; i <= end; i++) {
					const occurredAt = `2026-07-10T01:${String(i).padStart(2, '0')}:00Z`;
					items.push({ type:'message', occurredAt, sortId:`message:m${i}`, payload:{ id:`m${i}`, channel:'chatapp', direction:'inbound', timestamp:occurredAt, text:`message-${i}` } });
                  }
				  return { items, nextCursor, threadRevision, itemCount };
                }

                function classList() {
                  const values = new Set();
                  return {
                    add: value => values.add(value),
                    remove: value => values.delete(value),
                    contains: value => values.has(value),
                    toggle: (value, force) => force === undefined ? (values.has(value) ? values.delete(value) : values.add(value)) : (force ? values.add(value) : values.delete(value))
                  };
                }

                function element(id) {
                  if (elements.has(id)) return elements.get(id);
                  const el = {
                    id,
                    dataset: {},
                    style: { display:'', setProperty() {} },
                    classList: classList(),
                    hidden: false,
                    disabled: false,
                    textContent: '',
                    value: '',
                    clientHeight: id === 'thread' ? 120 : 0,
                    scrollHeight: id === 'thread' ? 120 : 0,
                    scrollTop: 0,
                    querySelectorAll: () => [],
                    closest: () => null,
                    appendChild() {},
                    remove() {},
                    click() {},
                    addEventListener() {},
                    focus() {}
                  };
                  Object.defineProperty(el, 'innerHTML', {
                    get() { return this._innerHTML || ''; },
                    set(value) {
                      this._innerHTML = String(value || '');
                      if (id === 'thread') {
                        const count = (this._innerHTML.match(/class="message-row/g) || []).length;
                        this.scrollHeight = Math.max(120, count * 40);
                      }
                    }
                  });
                  elements.set(id, el);
                  return el;
                }

                const document = {
                  activeElement: null,
                  body: { appendChild() {} },
                  addEventListener() {},
                  createElement: tag => element(`created-${tag}-${elements.size}`),
                  getElementById: id => element(id),
                  querySelectorAll(selector) {
                    if (selector === '.msg[data-id]') {
                      const thread = element('thread');
                      return [...thread.innerHTML.matchAll(/data-id="([^"]+)"/g)].map(match => ({
                        dataset: { id: match[1] },
                        classList: classList(),
                        onclick: null
                      }));
                    }
                    return [];
                  }
                };

                const context = {
                  assert,
                  console,
                  document,
                  page,
                  requests,
                  responses,
				  fetch: async (url, options = {}) => {
					requests.push(String(url));
					assert.equal(options.headers['X-WeCom-Viewer-Auth'], 'viewer-token');
					const body = responses.shift();
					assert.ok(body, `unexpected fetch ${url}`);
					return { ok:true, status:200, statusText:'', text:async () => JSON.stringify(body), json:async () => body, headers:{ get:() => '' }, blob:async () => ({ type:'' }) };
                  },
                  setTimeout,
                  clearTimeout,
                  requestAnimationFrame: callback => callback(),
                  window: { crypto: { randomUUID: () => 'uuid-1' }, open: () => null, toastTimer: null },
                  crypto: { randomUUID: () => 'uuid-1' },
                  URL: { createObjectURL: () => 'blob:test', revokeObjectURL() {} },
                  Notification: function Notification() {}
                };
                context.window.document = document;
                context.globalThis = context;
                vm.createContext(context);

                await vm.runInContext(script + `
				(async () => {
				  state.contacts = [{ id:'contact-1', displayName:'Buyer', channels:['chatapp'], points:[{ id:'contact-1', channel:'chatapp', value:'8613000000000' }] }];
				  state.selectedPointId = 'contact-1';
				  state.wecomAuth = { viewerAuthToken:'viewer-token' };
				  state.wecomAuthExpiresAt = Date.now() + 60000;

                  await loadThread('contact-1', false);
				  assert.equal(requests[0], '/api/v1/contacts/contact-1/timeline?limit=15');
                  assert.equal(state.threadPages['contact-1'].items.length, 10);
				  assert.equal(JSON.stringify(state.threadPages['contact-1'].items.map(item => item.payload.id)), JSON.stringify(['m11','m12','m13','m14','m15','m16','m17','m18','m19','m20']));
                  assert.equal(state.threadPages['contact-1'].threadRevision, 'rev-1');
                  assert.equal($('thread').scrollTop, $('thread').scrollHeight);

                  $('thread').scrollTop = 0;
                  const oldScrollHeight = $('thread').scrollHeight;
                  await loadOlderThreadMessages();
				  assert.equal(requests[1], '/api/v1/contacts/contact-1/timeline?limit=15&cursor=cursor-older');
                  assert.equal(state.threadPages['contact-1'].items.length, 20);
				  assert.equal(state.threadPages['contact-1'].items[0].payload.id, 'm1');
				  assert.equal(state.threadPages['contact-1'].items[19].payload.id, 'm20');
                  assert.equal($('thread').scrollTop, $('thread').scrollHeight - oldScrollHeight);

                  state.threadPages['contact-1'].nextCursor = 'stale-cursor';
                  $('thread').scrollTop = 0;
                  await loadOlderThreadMessages();
				  assert.equal(requests[2], '/api/v1/contacts/contact-1/timeline?limit=15&cursor=stale-cursor');
				  assert.equal(requests[3], '/api/v1/contacts/contact-1/timeline?limit=15');
				  assert.equal(JSON.stringify(state.threadPages['contact-1'].items.map(item => item.payload.id)), JSON.stringify(['m21','m22','m23','m24','m25','m26','m27','m28','m29','m30']));
                  assert.equal(state.threadPages['contact-1'].threadRevision, 'rev-2');
                  assert.equal(state.threadPages['contact-1'].nextCursor, 'cursor-fresh');
                })()
                `, context);

                console.log('frontend thread pagination behavior ok');
                """;
    }

    private static void frontendThreadPageCacheIsBounded() throws Exception {
        String html = App.pageHtml();

        assertContains(html, "const THREAD_PAGE_CACHE_LIMIT = 20;");
        assertContains(html, "const THREAD_PAGE_MAX_MESSAGES = 200;");
        assertContains(html, "threadPageAccessOrder:[]");
        assertContains(html, "function rememberThreadPageAccess(id)");
        assertContains(html, "function limitThreadPageMessages(page)");
        assertContains(html, "limitThreadPageMessages(state.threadPages[id]);");
        assertContains(html, "rememberThreadPageAccess(id);");

        Path dir = Files.createTempDirectory("message-center-frontend-cache-bound-test");
        Path pageHtml = dir.resolve("page.html");
        Path probe = dir.resolve("probe.mjs");
        Files.writeString(pageHtml, html, StandardCharsets.UTF_8);
        Files.writeString(probe, frontendThreadPageCacheProbe(), StandardCharsets.UTF_8);

        Process process = new ProcessBuilder("node", probe.toString(), pageHtml.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("frontend thread cache probe timed out");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("frontend thread cache probe failed:\n" + output);
        }
    }

    private static String frontendThreadPageCacheProbe() {
        return """
                import assert from 'node:assert/strict';
                import fs from 'node:fs';
                import vm from 'node:vm';

                const html = fs.readFileSync(process.argv[2], 'utf8');
                let script = html.match(/<script>([\\s\\S]*?)<\\/script>/)?.[1];
                assert.ok(script, 'page must contain the embedded script');
                script = script.replace(/\\n\\s*init\\(\\)\\.catch\\(err => toast\\(err\\.message\\)\\);\\s*$/, '');
                script = script.replace(/\\n\\s*initWeComLogin\\(\\)\\.catch\\(showWeComLoginError\\);\\s*$/, '');

                const requests = [];
                const responses = [];
                const elements = new Map();

                function page(start, end, nextCursor, threadRevision, itemCount) {
                  const items = [];
                  for (let i = start; i <= end; i++) {
					const occurredAt = `2026-07-10T01:${String(i).padStart(2, '0')}:00Z`;
					items.push({ type:'message', occurredAt, sortId:`message:m${i}`, payload:{ id:`m${i}`, channel:'chatapp', direction:'inbound', timestamp:occurredAt, text:`message-${i}` } });
                  }
				  return { items, nextCursor, threadRevision, itemCount };
                }

                function classList() {
                  const values = new Set();
                  return {
                    add: value => values.add(value),
                    remove: value => values.delete(value),
                    contains: value => values.has(value),
                    toggle: (value, force) => force === undefined ? (values.has(value) ? values.delete(value) : values.add(value)) : (force ? values.add(value) : values.delete(value))
                  };
                }

                function element(id) {
                  if (elements.has(id)) return elements.get(id);
                  const el = {
                    id,
                    dataset: {},
                    style: { display:'', setProperty() {} },
                    classList: classList(),
                    hidden: false,
                    disabled: false,
                    textContent: '',
                    value: '',
                    clientHeight: id === 'thread' ? 120 : 0,
                    scrollHeight: id === 'thread' ? 120 : 0,
                    scrollTop: 0,
                    querySelectorAll: () => [],
                    closest: () => null,
                    appendChild() {},
                    remove() {},
                    click() {},
                    addEventListener() {},
                    focus() {}
                  };
                  Object.defineProperty(el, 'innerHTML', {
                    get() { return this._innerHTML || ''; },
                    set(value) {
                      this._innerHTML = String(value || '');
                      if (id === 'thread') {
                        const count = (this._innerHTML.match(/class="message-row/g) || []).length;
                        this.scrollHeight = Math.max(120, count * 40);
                      }
                    }
                  });
                  elements.set(id, el);
                  return el;
                }

                const document = {
                  activeElement: null,
                  body: { appendChild() {} },
                  addEventListener() {},
                  createElement: tag => element(`created-${tag}-${elements.size}`),
                  getElementById: id => element(id),
                  querySelectorAll(selector) {
                    if (selector === '.msg[data-id]') {
                      const thread = element('thread');
                      return [...thread.innerHTML.matchAll(/data-id="([^"]+)"/g)].map(match => ({
                        dataset: { id: match[1] },
                        classList: classList(),
                        onclick: null
                      }));
                    }
                    return [];
                  }
                };

                const context = {
                  assert,
                  console,
                  document,
                  page,
                  requests,
                  responses,
				  fetch: async (url, options = {}) => {
					requests.push(String(url));
					assert.equal(options.headers['X-WeCom-Viewer-Auth'], 'viewer-token');
					const body = responses.shift();
					assert.ok(body, `unexpected fetch ${url}`);
					return { ok:true, status:200, statusText:'', text:async () => JSON.stringify(body), json:async () => body, headers:{ get:() => '' }, blob:async () => ({ type:'' }) };
                  },
                  setTimeout,
                  clearTimeout,
                  requestAnimationFrame: callback => callback(),
                  window: { crypto: { randomUUID: () => 'uuid-1' }, open: () => null, toastTimer: null },
                  crypto: { randomUUID: () => 'uuid-1' },
                  URL: { createObjectURL: () => 'blob:test', revokeObjectURL() {} },
                  Notification: function Notification() {}
                };
                context.window.document = document;
                context.globalThis = context;
                vm.createContext(context);

				await vm.runInContext(script + `
				(async () => {
				  state.wecomAuth = { viewerAuthToken:'viewer-token' };
				  state.wecomAuthExpiresAt = Date.now() + 60000;
				  for (let i = 1; i <= 25; i++) {
                    const id = 'contact-' + i;
                    state.contacts.push({ id, displayName:'Buyer ' + i, channels:['chatapp'], points:[{ id, channel:'chatapp', value:'86130000000' + i }] });
                    state.selectedPointId = id;
                    responses.push(page(i * 10 + 1, i * 10 + 10, null, 'rev-' + i, 10));
                    await loadThread(id, false);
                  }

                  assert.equal(Object.keys(state.threadPages).length, THREAD_PAGE_CACHE_LIMIT);
                  assert.equal(state.threadPages['contact-1'], undefined);
                  assert.ok(state.threadPages['contact-25']);
                  assert.equal(state.threadLoadSeqByContact['contact-1'], 1);

                  const oversized = limitThreadPageMessages(page(1, 250, 'too-old', 'rev-long', 250));
                  assert.equal(oversized.items.length, THREAD_PAGE_MAX_MESSAGES);
				  assert.equal(oversized.items[0].payload.id, 'm51');
				  assert.equal(oversized.items[199].payload.id, 'm250');
                  assert.equal(oversized.nextCursor, null);
                })()
                `, context);

                console.log('frontend thread cache bounds ok');
                """;
    }

    private static void eventHubRemovesDeadClientsWithoutWaitingForBusinessMessages() throws Exception {
        Class<?> eventHubClass = Class.forName(App.class.getName() + "$EventHub");
        java.lang.reflect.Constructor<?> constructor = eventHubClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object hub = constructor.newInstance();
        java.lang.reflect.Method connect = eventHubClass.getDeclaredMethod("connect", HttpExchange.class);
        java.lang.reflect.Method heartbeat = eventHubClass.getDeclaredMethod("heartbeat");
        java.lang.reflect.Method close = eventHubClass.getDeclaredMethod("close");
        java.lang.reflect.Field clients = eventHubClass.getDeclaredField("clients");
        connect.setAccessible(true);
        heartbeat.setAccessible(true);
        close.setAccessible(true);
        clients.setAccessible(true);

        try {
            CloseAwareHttpExchange healthy = new CloseAwareHttpExchange("GET", "/events");
            connect.invoke(hub, healthy);
            assertEquals(1, ((List<?>) clients.get(hub)).size());

            healthy.failWrites = true;
            heartbeat.invoke(hub);
            assertEquals(0, ((List<?>) clients.get(hub)).size());
            assertTrue(healthy.closed, "heartbeat should close stale SSE exchange");

            CloseAwareHttpExchange failingInitialWrite = new CloseAwareHttpExchange("GET", "/events");
            failingInitialWrite.failWrites = true;
            try {
                connect.invoke(hub, failingInitialWrite);
                throw new AssertionError("connect should surface initial SSE write failure");
            } catch (java.lang.reflect.InvocationTargetException exception) {
                assertTrue(exception.getCause() instanceof IOException, "connect failure should be an IOException");
            }
            assertEquals(0, ((List<?>) clients.get(hub)).size());
            assertTrue(failingInitialWrite.closed, "failed initial SSE exchange should be closed");

            HeaderFailingHttpExchange failingHeaders = new HeaderFailingHttpExchange("GET", "/events");
            try {
                connect.invoke(hub, failingHeaders);
                throw new AssertionError("connect should surface SSE header failure");
            } catch (java.lang.reflect.InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                assertTrue(cause instanceof IOException, "header failure should be an IOException but was " + cause);
            }
            assertEquals(0, ((List<?>) clients.get(hub)).size());
            assertTrue(failingHeaders.closed, "failed SSE header exchange should be closed");
        } finally {
            close.invoke(hub);
        }
    }

    private static void eventHubSerializesWritesPerSseClient() throws Exception {
        Class<?> eventHubClass = Class.forName(App.class.getName() + "$EventHub");
        java.lang.reflect.Constructor<?> constructor = eventHubClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object hub = constructor.newInstance();
        java.lang.reflect.Method connect = eventHubClass.getDeclaredMethod("connect", HttpExchange.class);
        java.lang.reflect.Method heartbeat = eventHubClass.getDeclaredMethod("heartbeat");
        java.lang.reflect.Method publish = eventHubClass.getDeclaredMethod("publish", UnifiedMessage.class);
        java.lang.reflect.Method close = eventHubClass.getDeclaredMethod("close");
        connect.setAccessible(true);
        heartbeat.setAccessible(true);
        publish.setAccessible(true);
        close.setAccessible(true);

        ConcurrentWriteHttpExchange exchange = new ConcurrentWriteHttpExchange("GET", "/events");
        try {
            connect.invoke(hub, exchange);
            exchange.blockWrites = true;
            Thread heartbeatThread = new Thread(() -> invokeUnchecked(heartbeat, hub), "sse-heartbeat-test");
            heartbeatThread.start();
            assertTrue(exchange.firstWriteEntered.await(2, TimeUnit.SECONDS), "heartbeat write should enter response stream");

            Thread publishThread = new Thread(() -> invokeUnchecked(publish, hub, testSseMessage()), "sse-publish-test");
            publishThread.start();
            assertFalse(exchange.secondWriteEntered.await(150, TimeUnit.MILLISECONDS),
                    "business publish must not enter the same SSE stream while heartbeat write is active");

            exchange.releaseWrites.countDown();
            heartbeatThread.join(2000);
            publishThread.join(2000);
            assertFalse(heartbeatThread.isAlive(), "heartbeat test thread should finish");
            assertFalse(publishThread.isAlive(), "publish test thread should finish");
            assertFalse(exchange.concurrentWriteDetected.get(), "SSE response writes must be serialized per client");
        } finally {
            exchange.releaseWrites.countDown();
            close.invoke(hub);
        }
    }

    private static void eventHubPublishesTemplateChanges() throws Exception {
        Class<?> eventHubClass = Class.forName(App.class.getName() + "$EventHub");
        java.lang.reflect.Constructor<?> constructor = eventHubClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object hub = constructor.newInstance();
        java.lang.reflect.Method connect = eventHubClass.getDeclaredMethod("connect", HttpExchange.class);
        java.lang.reflect.Method publishTemplatesChanged = eventHubClass.getDeclaredMethod(
                "publishTemplatesChanged", int.class);
        java.lang.reflect.Method close = eventHubClass.getDeclaredMethod("close");
        connect.setAccessible(true);
        publishTemplatesChanged.setAccessible(true);
        close.setAccessible(true);
        FakeHttpExchange exchange = new FakeHttpExchange("GET", "/events");
        try {
            connect.invoke(hub, exchange);
            publishTemplatesChanged.invoke(hub, 2);
            assertEquals(": connected\n\nevent: templates-changed\n"
                    + "data: {\"count\":2}\n\n", exchange.responseText());
        } finally {
            close.invoke(hub);
        }

        String html = App.pageHtml();
        assertContains(html, "addEventListener('templates-changed'");
        assertContains(html, "await refreshTemplates()");
        int handlerStart = html.indexOf("addEventListener('templates-changed'");
        int handlerEnd = html.indexOf("});", handlerStart) + 3;
        String handler = html.substring(handlerStart, handlerEnd);
        assertNotContains(handler, "refreshAll(");
        assertNotContains(handler, "toast('有新消息')");
        assertNotContains(handler, "Notification");
    }

    private static void templateRouteServesLastSuccessfulSnapshot() throws Exception {
        Path dir = Files.createTempDirectory("message-center-template-route-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        TemplateStore.TemplateRecord record = new TemplateStore.TemplateRecord();
        record.templateCode = "stable-template";
        record.templateName = "stable-template";
        record.languageCode = "zh_CN";
        record.body = "Stable body";
        record.raw = "{\"auditStatus\":\"pass\"}";
        new TemplateStore(config.chatappTemplateFile()).replaceIfChanged(List.of(record));
        FakeHttpExchange exchange = new FakeHttpExchange("GET", "/api/templates");

        invokeRoute(exchange, config, new UnifiedMessageStore(config));

        assertEquals(200, exchange.responseCode);
        assertContains(exchange.responseText(), "stable-template");
        assertContains(exchange.responseText(), "Stable body");
    }

    private static UnifiedMessage testSseMessage() {
        UnifiedMessage message = new UnifiedMessage();
        message.id = "sse-message-1";
        message.channel = "chatapp";
        message.text = "hello";
        return message;
    }

    private static void invokeUnchecked(java.lang.reflect.Method method, Object target, Object... args) {
        try {
            method.invoke(target, args);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void chatAppTextMessagesUseMessageBodyAsContactPreview() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chat-preview-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Files.writeString(chatData.resolve("messages.jsonl"), "{"
                + "\"id\":\"chat-preview-1\","
                + "\"direction\":\"inbound\","
                + "\"timestamp\":\"2026-07-10T01:00:00Z\","
                + "\"from\":\"8613800000000\","
                + "\"to\":\"8613266259485\","
                + "\"text\":\"{\\\"text\\\":\\\"Actual WhatsApp body\\\"}\","
                + "\"raw\":\"{}\""
                + "}\n", StandardCharsets.UTF_8);

        UnifiedMessageStore store = testStore(dir, emailData, chatData.resolve("messages.jsonl"));

        List<UnifiedContact> contacts = store.contacts();
        assertEquals("Actual WhatsApp body", contacts.get(0).lastText);
    }

    private static void emailMessagesKeepBodyTextOutOfTimelineBubble() {
        String html = App.pageHtml();

        assertContains(html, "function bubbleText(m)");
        assertContains(html, "m.channel === 'email'");
        assertContains(html, "const text = bubbleText(m)");
        assertContains(html, "${text ? `<div class=\"msg-text\">${esc(text)}</div>` : ''}");
        assertContains(html, "<h3>内容</h3><div class=\"msg-text\">${esc(m.bodyText || m.text || '')}</div>");
        assertNotContains(html, "<div class=\"msg-text\">${esc(m.text || m.summary || '')}</div>");
    }

    private static void emailSendShowsValidationAndFailureFeedback() {
        String html = App.pageHtml();

        assertContains(html, "if (!to) { toast('请选择收件人'); return; }");
        assertContains(html, "if (!subject) { toast('请输入邮件主题'); return; }");
        assertContains(html, "button.disabled = true");
        assertContains(html, "toast(`邮件发送失败：${err.message}`)");
        assertContains(html, "button.disabled = false");
    }

    private static void mailSenderUsesSenderDomainForMessageId() throws Exception {
        assertEquals("139.com", MailSender.messageIdDomain("13266259485@139.com", "smtp.139.com"));
        String messageId = MailSender.messageIdHeader("13266259485@139.com", "smtp.139.com");
        assertContains(messageId, "@139.com>");
        assertNotContains(messageId, "@localhost");
        assertEquals("smtp.139.com", MailSender.smtpServername("smtp.139.com", "13266259485@139.com"));
        assertEquals("smtp.139.com", MailSender.smtpServername("10.0.0.8", "13266259485@139.com"));
        assertEquals("203.0.113.10", MailSender.firstIpv4Address(List.of(
                InetAddress.getByName("2001:db8::1"),
                InetAddress.getByName("203.0.113.10"))));
    }

    private static void emailSync139DirectModeConfiguresImapsTlsProfile() {
        Config config = new Config(Map.of(
                "MAIL_PROVIDER", "139",
                "IMAP_HOST", "imap.139.com",
                "IMAP_PORT", "993",
                "IMAP_SSL", "true",
                "IMAP_USERNAME", "sender@139.com",
                "IMAP_PASSWORD", "secret"
        ));
        Properties props = new EmailSyncService(config).imapProperties();

        assertEquals("imap.139.com", props.getProperty("mail.imaps.host"));
        assertEquals("993", props.getProperty("mail.imaps.port"));
        assertEquals("true", props.getProperty("mail.imaps.ssl.enable"));
        assertEquals("TLSv1.2", props.getProperty("mail.imaps.ssl.protocols"));
        assertEquals("TLS_RSA_WITH_AES_256_GCM_SHA384", props.getProperty("mail.imaps.ssl.ciphersuites"));
    }

    private static void emailSync139DirectModeInstallsDedicatedSocketFactory() {
        Config config = new Config(Map.of(
                "MAIL_PROVIDER", "139",
                "IMAP_HOST", "imap.139.com",
                "IMAP_PORT", "993",
                "IMAP_SSL", "true",
                "IMAP_USERNAME", "sender@139.com",
                "IMAP_PASSWORD", "secret"
        ));
        Properties props = new EmailSyncService(config).imapProperties();

        assertContains(props.get("mail.imaps.ssl.socketFactory").getClass().getName(), "BouncyCastle");

        Properties defaultProps = new EmailSyncService(new Config(Map.of(
                "MAIL_PROVIDER", "default",
                "IMAP_HOST", "imap.qq.com",
                "IMAP_PORT", "993",
                "IMAP_SSL", "true",
                "IMAP_USERNAME", "sender@qq.com",
                "IMAP_PASSWORD", "secret"
        ))).imapProperties();
        assertNull(defaultProps.get("mail.imaps.ssl.socketFactory"), "default provider must use the JDK socket factory");
    }

    private static void emailSync139UsesOpenSslImapFallback() {
        Config config = new Config(Map.of(
                "MAIL_PROVIDER", "139",
                "IMAP_HOST", "imap.139.com",
                "IMAP_PORT", "993",
                "IMAP_SSL", "true",
                "IMAP_USERNAME", "sender@139.com",
                "IMAP_PASSWORD", "secret"
        ));

        assertTrue(new EmailSyncService(config).usesOpenSslImapFallback(), "139 should bypass JavaMail IMAP login");
        assertFalse(new EmailSyncService(new Config(Map.of(
                "MAIL_PROVIDER", "default",
                "IMAP_HOST", "imap.qq.com",
                "IMAP_PORT", "993",
                "IMAP_SSL", "true",
                "IMAP_USERNAME", "sender@qq.com",
                "IMAP_PASSWORD", "secret"
        ))).usesOpenSslImapFallback(), "default provider should keep JavaMail IMAP");
    }

    private static void openSslImapClientExtractsRfc822Literals() throws Exception {
        String first = "Subject: One\r\n\r\nBody one";
        String second = "Subject: Two\r\n\r\nBody two";
        byte[] response = ("* 1 FETCH (RFC822 {" + first.getBytes(StandardCharsets.UTF_8).length + "}\r\n"
                + first + "\r\n)\r\n"
                + "* 2 FETCH (RFC822 {" + second.getBytes(StandardCharsets.UTF_8).length + "}\r\n"
                + second + "\r\n)\r\n"
                + "A004 OK FETCH completed\r\n").getBytes(StandardCharsets.UTF_8);

        List<byte[]> messages = OpenSslImapClient.extractLiterals(
                new java.io.ByteArrayInputStream(response), "A004");

        assertEquals(2, messages.size());
        assertEquals(first, new String(messages.get(0), StandardCharsets.UTF_8));
        assertEquals(second, new String(messages.get(1), StandardCharsets.UTF_8));
    }

    private static void emailInboxWriterStoresImapMessagesInLegacyInboxJsonlFormat() throws Exception {
        Path dir = Files.createTempDirectory("message-center-email-sync-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        EmailInboxWriter writer = new EmailInboxWriter(config);

        MimeMessage incoming = mailMessage(
                "Buyer",
                "buyer@example.com",
                "seller@example.com",
                "Need quote",
                "Please send price.",
                "<mail-1@example.com>",
                "2026-07-10T01:00:00Z"
        );
        MimeMessage sent = mailMessage(
                "Demo Seller",
                "seller@example.com",
                "Buyer <buyer@example.com>",
                "Quote sent",
                "Here is the quote.",
                "<mail-2@example.com>",
                "2026-07-10T01:05:00Z"
        );

        assertEquals("true", Boolean.toString(writer.append(incoming, "in")));
        assertEquals("false", Boolean.toString(writer.append(incoming, "in")));
        assertEquals("true", Boolean.toString(writer.append(sent, "out")));

        List<String> lines = Files.readAllLines(emailData.resolve("inbox.jsonl"), StandardCharsets.UTF_8);
        assertEquals("2", Integer.toString(lines.size()));
        assertContains(lines.get(0), "\"direction\":\"in\"");
        assertContains(lines.get(0), "\"contactEmail\":\"buyer@example.com\"");
        assertContains(lines.get(0), "\"bodyText\":\"Please send price.\"");
        assertContains(lines.get(1), "\"direction\":\"out\"");
        assertContains(lines.get(1), "\"to\":\"Buyer <buyer@example.com>\"");
    }

    private static void chatAppHistoryStoreDeduplicatesAndFeedsUnifiedTimeline() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        ChatAppHistoryStore historyStore = new ChatAppHistoryStore(config);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("mediaType", "text");

        assertEquals("true", Boolean.toString(historyStore.append("chat-sync-1", "inbound",
                "8613800000000", "8613266259485", "Synced hello", "Read", "{}", extra)));
        assertEquals("false", Boolean.toString(historyStore.append("chat-sync-1", "inbound",
                "8613800000000", "8613266259485", "Synced hello", "Read", "{}", extra)));

        List<String> lines = Files.readAllLines(chatData.resolve("messages.jsonl"), StandardCharsets.UTF_8);
        assertEquals("1", Integer.toString(lines.size()));
        List<UnifiedMessage> thread = new UnifiedMessageStore(config).thread("chatapp:whatsapp:8613800000000");
        assertEquals("1", Integer.toString(thread.size()));
        assertEquals("Synced hello", thread.get(0).text);
        assertEquals("Read", thread.get(0).status);
    }

    private static void chatAppWebhookDelegatesWritesToHistoryStore() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-webhook-owner-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        AtomicInteger writes = new AtomicInteger();
        ChatAppHistoryStore history = new ChatAppHistoryStore(config) {
            @Override
            public WriteResult appendResult(String id, String direction, String from, String to,
                                            String text, String status, String timestamp,
                                            String raw, Map<String, String> extra) throws Exception {
                writes.incrementAndGet();
                return super.appendResult(id, direction, from, to, text, status, timestamp, raw, extra);
            }
        };
        ChatAppSender sender = new ChatAppSender(config, history);

        sender.appendWebhook("{\"MessageId\":\"webhook-owner-1\",\"From\":\"user\","
                + "\"To\":\"business\",\"Message\":\"hello\"}");

        assertEquals("1", Integer.toString(writes.get()));
        assertContains(Files.readString(config.chatappDataFile()), "webhook-owner-1");
    }

    private static void chatAppStatusWebhookPreservesLegacyProjection() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-status-webhook-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        ChatAppHistoryStore history = new ChatAppHistoryStore(config);
        history.appendResult("webhook-status-1", "outbound", "business", "user", "hello", null,
                "2026-08-01T00:00:00Z", "{}", Map.of());
        ChatAppSender sender = new ChatAppSender(config, history);

        UnifiedMessage result = sender.appendWebhook("{\"MessageId\":\"webhook-status-1\","
                + "\"From\":\"business\",\"To\":\"user\",\"Status\":\"Read\"}");

        String stored = Files.readString(config.chatappDataFile());
        assertContains(stored, "\"id\":\"webhook-status-1-status\"");
        assertContains(stored, "\"direction\":\"status\"");
        assertContains(stored, "\"text\":\"Status: Read\"");
        assertNotContains(stored, "\"status\":\"Read\"");
        assertEquals("webhook-status-1", result.sourceId);
        assertEquals("outbound", result.direction);
        assertEquals("hello", result.text);
        assertEquals("Read", result.status);
    }

    private static void chatAppSyncRouteUsesSynchronizerAndPreservesLockBusyResult() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-route-test");
        Path emailData = dir.resolve("email");
        Path messages = dir.resolve("messages.jsonl");
        Files.createDirectories(emailData);
        Config config = testConfig(dir, emailData, messages);
        UnifiedMessageStore store = new UnifiedMessageStore(config);
        AtomicInteger syncCalls = new AtomicInteger();
        ChatAppMessageSynchronizer synchronizer = new ChatAppMessageSynchronizer(
                messages, gate -> {
                    syncCalls.incrementAndGet();
                    SyncResult result = new SyncResult("chatapp");
                    result.message = "unexpected";
                    return result;
                }, () -> { });
        FakeHttpExchange exchange = new FakeHttpExchange("POST", "/api/sync/chatapp");

        try (ChatAppMessageSyncLock.Handle ignored =
                     ChatAppMessageSyncLock.tryAcquire(messages).orElseThrow(); synchronizer) {
            App.routeForTests(exchange, config, store, synchronizer);
        }

        assertEquals(200, exchange.responseCode);
        JsonObject response = JsonParser.parseString(exchange.responseText()).getAsJsonObject();
        assertEquals("chatapp", response.get("channel").getAsString());
        assertEquals("lock_busy", response.get("message").getAsString());
        for (String field : List.of(
                "fetched", "saved", "updated", "skipped", "pages", "durationMillis",
                "syncStartTime", "syncEndTime", "mediaCached", "mediaQueued", "mediaFailed",
                "mediaFailures", "templatesFetched", "templatesSaved", "templatesSkipped")) {
            assertTrue(response.has(field), "missing SyncResult field: " + field);
        }
        assertEquals(0, syncCalls.get());
    }

    private static void chatAppHistoryStoreRefreshesExpiredMediaUrlForExistingMessage() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-media-refresh-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        ChatAppHistoryStore historyStore = new ChatAppHistoryStore(config);
        Map<String, String> oldExtra = new LinkedHashMap<>();
        oldExtra.put("mediaType", "image");
        oldExtra.put("mediaUrl", "https://oss.example.com/expired.png");
        oldExtra.put("fileName", "photo.png");
        Map<String, String> newExtra = new LinkedHashMap<>();
        newExtra.put("mediaType", "image");
        newExtra.put("mediaUrl", "https://oss.example.com/fresh.png");
        newExtra.put("fileName", "photo.png");

        assertEquals("true", Boolean.toString(historyStore.append("chat-media-refresh-1", "inbound",
                "8613800000000", "8613266259485", "[image] photo.png", "Read", "{\"link\":\"https://oss.example.com/expired.png\"}", oldExtra)));
        assertEquals("true", Boolean.toString(historyStore.append("chat-media-refresh-1", "inbound",
                "8613800000000", "8613266259485", "[image] photo.png", "Read", "{\"link\":\"https://oss.example.com/fresh.png\"}", newExtra)));

        List<String> lines = Files.readAllLines(chatData.resolve("messages.jsonl"), StandardCharsets.UTF_8);
        assertEquals("1", Integer.toString(lines.size()));
        assertContains(lines.get(0), "https://oss.example.com/fresh.png");
        assertNotContains(lines.get(0), "https://oss.example.com/expired.png");
        List<UnifiedMessage> thread = new UnifiedMessageStore(config).thread("chatapp:whatsapp:8613800000000");
        assertEquals("https://oss.example.com/fresh.png", thread.get(0).mediaUrl);
    }

    private static void chatAppHistorySyncUsesLatestLocalTimestampForIncrementalWindow() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-incremental-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"), Map.of(
                "SYNC_INCREMENTAL", "true",
                "SYNC_OVERLAP_MINUTES", "30",
                "SYNC_LOOKBACK_DAYS", "10"
        ));
        ChatAppHistoryStore historyStore = new ChatAppHistoryStore(config);
        historyStore.append("old-message", "inbound", "8613800000000", "8613266259485",
                "old", "Read", "2026-07-01T00:00:00Z", "{}", Map.of());
        historyStore.append("latest-message", "inbound", "8613800000000", "8613266259485",
                "latest", "Read", "2026-07-10T08:00:00Z", "{}", Map.of());
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, historyStore,
                new TemplateStore(config.chatappTemplateFile()), message -> {});
        java.lang.reflect.Method syncStartTime = ChatAppHistorySyncService.class.getDeclaredMethod("syncStartTime");
        syncStartTime.setAccessible(true);

        long startTime = (Long) syncStartTime.invoke(service);

        assertEquals("2026-07-10T07:30:00Z", Instant.ofEpochMilli(startTime).toString());
    }

    private static void chatAppHistorySyncDefaultsToShortFirstRunWindow() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-default-window-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> {});
        java.lang.reflect.Method syncStartTime = ChatAppHistorySyncService.class.getDeclaredMethod("syncStartTime");
        syncStartTime.setAccessible(true);

        long earliest = Instant.now().minus(Duration.ofHours(25)).toEpochMilli();
        long latest = Instant.now().minus(Duration.ofHours(23)).toEpochMilli();
        long startTime = (Long) syncStartTime.invoke(service);

        assertTrue(startTime >= earliest && startTime <= latest,
                "default first sync should look back about 1 day, got " + Instant.ofEpochMilli(startTime));
    }

    private static void chatAppHistorySyncPreCachesMediaWithoutBlockingMessageSync() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-media-cache-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"), Map.of(
                "CHATAPP_MEDIA_PRECACHE_MODE", "inline"
        ));
        AtomicInteger cached = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> {
                    if (message.mediaUrl.contains("fail")) {
                        failed.incrementAndGet();
                        throw new IOException("download denied");
                    }
                    cached.incrementAndGet();
                });
        SyncResult result = new SyncResult("chatapp");

        service.appendAndPrecache(projectedMedia("chat-media-cache-ok", "https://oss.example.com/ok.png"), result);
        service.appendAndPrecache(projectedMedia("chat-media-cache-fail", "https://oss.example.com/fail.png"), result);

        assertEquals("2", Integer.toString(result.saved));
        assertEquals("1", Integer.toString(result.mediaCached));
        assertEquals("1", Integer.toString(result.mediaFailed));
        assertEquals("1", Integer.toString(cached.get()));
        assertEquals("1", Integer.toString(failed.get()));
        @SuppressWarnings("unchecked")
        List<Object> failures = (List<Object>) SyncResult.class.getField("mediaFailures").get(result);
        assertEquals("1", Integer.toString(failures.size()));
        assertContains(failures.get(0).toString(), "chat-media-cache-fail");
        assertContains(failures.get(0).toString(), "download denied");
        List<String> lines = Files.readAllLines(chatData.resolve("messages.jsonl"), StandardCharsets.UTF_8);
        assertEquals("2", Integer.toString(lines.size()));
        assertContains(lines.get(0), "https://oss.example.com/ok.png");
        assertContains(lines.get(1), "https://oss.example.com/fail.png");
    }

    private static void chatAppHistorySyncQueuesMediaPrecacheByDefaultWithoutBlockingMessageSync() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-media-background-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"));
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger cacheAttempts = new AtomicInteger();
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> {
                    release.await(2, TimeUnit.SECONDS);
                    cacheAttempts.incrementAndGet();
                });
        SyncResult result = new SyncResult("chatapp");

        long started = System.nanoTime();
        service.appendAndPrecache(projectedMedia("chat-media-cache-background", "https://oss.example.com/background.png"), result);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();

        assertTrue(elapsedMillis < 500, "media cache blocked sync for " + elapsedMillis + "ms");
        assertEquals("1", Integer.toString(result.saved));
        assertEquals("1", Integer.toString(result.mediaQueued));
        assertEquals("0", Integer.toString(result.mediaCached));
    }

    private static void chatAppHistorySyncDoesNotRetryUnchangedMediaByDefault() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-media-skip-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"), Map.of(
                "CHATAPP_MEDIA_PRECACHE_MODE", "inline"
        ));
        AtomicInteger cacheAttempts = new AtomicInteger();
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> cacheAttempts.incrementAndGet());
        SyncResult result = new SyncResult("chatapp");

        service.appendAndPrecache(projectedMedia("chat-media-cache-stable", "https://oss.example.com/stable.png"), result);
        service.appendAndPrecache(projectedMedia("chat-media-cache-stable", "https://oss.example.com/stable.png"), result);

        assertEquals("1", Integer.toString(result.saved));
        assertEquals("1", Integer.toString(result.skipped));
        assertEquals("1", Integer.toString(result.mediaCached));
        assertEquals("1", Integer.toString(cacheAttempts.get()));
    }

    private static void chatAppHistorySyncExtractsCamsUrlFieldForMediaCache() throws Exception {
        Path dir = Files.createTempDirectory("message-center-chatapp-sync-cams-url-test");
        Path emailData = dir.resolve("email");
        Path chatData = dir.resolve("chatapp");
        Files.createDirectories(emailData);
        Files.createDirectories(chatData);
        Config config = testConfig(dir, emailData, chatData.resolve("messages.jsonl"), Map.of(
                "CHATAPP_MEDIA_PRECACHE_MODE", "inline"
        ));
        AtomicReference<String> cachedUrl = new AtomicReference<>("");
        ChatAppHistorySyncService service = new ChatAppHistorySyncService(config, new ChatAppHistoryStore(config),
                new TemplateStore(config.chatappTemplateFile()), message -> cachedUrl.set(message.mediaUrl));
        ListChatappMessageResponseBody.Data row = ListChatappMessageResponseBody.Data.builder()
                .messageId("chat-cams-url-1")
                .businessNumber("8613266259485")
                .userNumber("8613428277520")
                .eventAction("DOWN")
                .messageSource("api")
                .messageTypeName("image")
                .messageStatusName("Success")
                .sendTime("2026-07-10T08:28:34.836+00:00")
                .message("{\"caption\":\"营业执照\",\"mediaType\":\"image\",\"url\":\"https://oss.example.com/fresh.png\",\"fileName\":\"fresh.png\",\"mimeType\":\"image/png\"}")
                .build();
        java.lang.reflect.Method project = ChatAppHistorySyncService.class.getDeclaredMethod("project", ListChatappMessageResponseBody.Data.class);
        project.setAccessible(true);
        ChatAppHistorySyncService.ProjectedChatAppMessage projected =
                (ChatAppHistorySyncService.ProjectedChatAppMessage) project.invoke(service, row);
        SyncResult result = new SyncResult("chatapp");

        service.appendAndPrecache(projected, result);

        assertEquals("1", Integer.toString(result.mediaCached));
        assertEquals("https://oss.example.com/fresh.png", cachedUrl.get());
        List<String> lines = Files.readAllLines(chatData.resolve("messages.jsonl"), StandardCharsets.UTF_8);
        assertContains(lines.get(0), "\"mediaUrl\":\"https://oss.example.com/fresh.png\"");
        assertContains(lines.get(0), "\"mimeType\":\"image/png\"");
        assertContains(lines.get(0), "\"fileName\":\"fresh.png\"");
    }

    private static ChatAppHistorySyncService.ProjectedChatAppMessage projectedMedia(String id, String mediaUrl) {
        ChatAppHistorySyncService.ProjectedChatAppMessage message = new ChatAppHistorySyncService.ProjectedChatAppMessage();
        message.id = id;
        message.direction = "outbound";
        message.from = "8613266259485";
        message.to = "8613428277520";
        message.text = "[image]";
        message.status = "Success";
        message.timestamp = "2026-07-10T08:28:34Z";
        message.raw = "{\"messageTypeName\":\"image\"}";
        message.extra.put("mediaType", "image");
        message.extra.put("mediaUrl", mediaUrl);
        message.extra.put("mimeType", "image/png");
        message.extra.put("fileName", id + ".png");
        return message;
    }

    private static UnifiedMessageStore testStore(Path dir, Path emailData, Path chatFile) {
        return new UnifiedMessageStore(testConfig(dir, emailData, chatFile));
    }

    private static Config testConfig(Path dir, Path emailData, Path chatFile) {
        return testConfig(dir, emailData, chatFile, Map.of());
    }

    private static Config testConfig(Path dir, Path emailData, Path chatFile, Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>();
        values.put("DATA_DIR", dir.toString());
        values.put("EMAIL_DATA_DIR", emailData.toString());
        values.put("CHATAPP_DATA_FILE", chatFile.toString());
        values.put("CHATAPP_TEMPLATE_FILE", dir.resolve("templates.json").toString());
        values.put("CONTACT_GROUP_FILE", dir.resolve("contact-groups.jsonl").toString());
        values.put("EMAIL_CONTACT_GROUP_FILE", emailData.resolve("contact-groups.jsonl").toString());
        values.putAll(overrides);
        return new Config(values);
    }

    private static MimeMessage mailMessage(
            String fromName,
            String fromEmail,
            String to,
            String subject,
            String body,
            String messageId,
            String sentAt
    ) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(fromEmail, fromName, "UTF-8"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        message.setSentDate(Date.from(Instant.parse(sentAt)));
        message.saveChanges();
        message.setHeader("Message-ID", messageId);
        return message;
    }

    private static void invokeRoute(FakeHttpExchange exchange, Config config, UnifiedMessageStore store) throws Exception {
        java.lang.reflect.Method route = Stream.of(App.class.getDeclaredMethods())
                .filter(method -> "route".equals(method.getName()))
                .findFirst()
                .orElseThrow();
        route.setAccessible(true);
        Object[] args = new Object[route.getParameterCount()];
        args[0] = exchange;
        args[1] = config;
        args[2] = store;
        try {
            route.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            if (cause instanceof Error error) throw error;
            throw exception;
        }
    }

    private static class RecordingThreadPageStore extends UnifiedMessageStore {
        String contactPointId;
        String cursor;
        int limit;

        RecordingThreadPageStore(Config config) {
            super(config);
        }

        @Override
        public ThreadPage threadPage(String contactPointId, String cursor, int limit) {
            this.contactPointId = contactPointId;
            this.cursor = cursor;
            this.limit = limit;
            UnifiedMessage message = new UnifiedMessage();
            message.id = "message-1";
            message.text = "hello";
            return new ThreadPage(List.of(message), "older-cursor");
        }
    }

    private static class FakeHttpExchange extends HttpExchange {
        private final String method;
        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private ByteArrayInputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        int responseCode;

        FakeHttpExchange(String method, String uri) {
            this.method = method;
            this.uri = URI.create(uri);
        }

        String responseText() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        void requestBodyJson(String json) {
            requestHeaders.set("Content-Type", "application/json");
            requestBody = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        }

        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int responseCode, long responseLength) throws IOException { this.responseCode = responseCode; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override public int getResponseCode() { return responseCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream input, OutputStream output) {}
        @Override public HttpPrincipal getPrincipal() { return null; }
    }

    private static class CloseAwareHttpExchange extends FakeHttpExchange {
        private final OutputStream failingResponseBody = new OutputStream() {
            @Override public void write(int b) throws IOException {
                if (failWrites) {
                    throw new IOException("client disconnected");
                }
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (failWrites) {
                    throw new IOException("client disconnected");
                }
            }

            @Override public void flush() throws IOException {
                if (failWrites) {
                    throw new IOException("client disconnected");
                }
            }
        };
        boolean closed;
        private boolean failWrites;

        CloseAwareHttpExchange(String method, String uri) {
            super(method, uri);
        }

        @Override public OutputStream getResponseBody() {
            return failingResponseBody;
        }

        @Override public void close() {
            closed = true;
        }
    }

    private static class HeaderFailingHttpExchange extends CloseAwareHttpExchange {
        HeaderFailingHttpExchange(String method, String uri) {
            super(method, uri);
        }

        @Override public void sendResponseHeaders(int responseCode, long responseLength) throws IOException {
            throw new IOException("client disconnected before headers");
        }
    }

    private static class ConcurrentWriteHttpExchange extends FakeHttpExchange {
        private final CountDownLatch firstWriteEntered = new CountDownLatch(1);
        private final CountDownLatch secondWriteEntered = new CountDownLatch(1);
        private final CountDownLatch releaseWrites = new CountDownLatch(1);
        private final AtomicInteger activeWrites = new AtomicInteger();
        private final AtomicBoolean concurrentWriteDetected = new AtomicBoolean();
        private boolean blockWrites;

        ConcurrentWriteHttpExchange(String method, String uri) {
            super(method, uri);
        }

        @Override public OutputStream getResponseBody() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException {
                    write(new byte[] { (byte) b }, 0, 1);
                }

                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (!blockWrites) {
                        return;
                    }
                    if (activeWrites.incrementAndGet() > 1) {
                        concurrentWriteDetected.set(true);
                        secondWriteEntered.countDown();
                    } else {
                        firstWriteEntered.countDown();
                    }
                    try {
                        try {
                            releaseWrites.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("interrupted", interrupted);
                        }
                    } finally {
                        activeWrites.decrementAndGet();
                    }
                }
            };
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]");
        }
    }

    private static void assertNotEquals(String unexpected, String actual, String message) {
        if (unexpected == null ? actual == null : unexpected.equals(actual)) {
            throw new AssertionError(message + ": " + actual);
        }
    }

    private static void assertContains(String value, String expected) {
        if (value == null || !value.contains(expected)) {
            throw new AssertionError("Expected [" + value + "] to contain [" + expected + "]");
        }
    }

    private static void assertNotContains(String value, String unexpected) {
        if (value != null && value.contains(unexpected)) {
            throw new AssertionError("Expected [" + value + "] not to contain [" + unexpected + "]");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static <T extends Throwable> T assertThrows(Class<T> expectedType, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) {
                return expectedType.cast(actual);
            }
            throw new AssertionError("Expected " + expectedType.getSimpleName()
                    + " but got " + actual.getClass().getSimpleName(), actual);
        }
        throw new AssertionError("Expected " + expectedType.getSimpleName() + " to be thrown");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static int emojiSetSize(String html) {
        String marker = "const emojiSet = [";
        int start = html.indexOf(marker);
        if (start < 0) {
            return 0;
        }
        int end = html.indexOf("];", start);
        if (end < 0) {
            return 0;
        }
        String body = html.substring(start + marker.length(), end).trim();
        if (body.isEmpty()) {
            return 0;
        }
        return body.split(",").length;
    }

    private static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + ": " + value);
        }
    }
}
