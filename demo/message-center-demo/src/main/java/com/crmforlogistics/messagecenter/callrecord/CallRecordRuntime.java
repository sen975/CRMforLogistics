package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import com.crmforlogistics.messagecenter.UnifiedMessageStore;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;

public final class CallRecordRuntime implements AutoCloseable {
    private final FileCallRecordRepository repository;
    private final TranscriptionWorker worker;
    private final CallRecordService service;
    private final LocalAudioStore audioStore;
    private final CallRecordException startupFailure;
    private volatile boolean closing;
    private volatile boolean closed;

    private CallRecordRuntime(FileCallRecordRepository repository,
                              TranscriptionWorker worker,
                              CallRecordService service,
                              LocalAudioStore audioStore,
                              CallRecordException startupFailure) {
        this.repository = repository;
        this.worker = worker;
        this.service = service;
        this.audioStore = audioStore;
        this.startupFailure = startupFailure;
    }

    public static CallRecordRuntime open(Config config,
                                         UnifiedMessageStore store,
                                         Consumer<CallRecordEvent> events) {
        return openInternal(config,
                store == null ? null : store::contactGroup,
                null, Clock.systemUTC(), events);
    }

    static CallRecordRuntime openForTests(
            Config config,
            CallRecordService.ContactGroups contactGroups,
            TranscriptionWorker.Transcriber transcriber,
            Clock clock,
            Consumer<CallRecordEvent> events) {
        return openInternal(config, contactGroups, transcriber, clock, events);
    }

    private static CallRecordRuntime openInternal(
            Config config,
            CallRecordService.ContactGroups contactGroups,
            TranscriptionWorker.Transcriber providedTranscriber,
            Clock clock,
            Consumer<CallRecordEvent> events) {
        FileCallRecordRepository repository = null;
        TranscriptionWorker worker = null;
        try {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(contactGroups, "contactGroups");
            Objects.requireNonNull(clock, "clock");
            LocalAudioStore audioStore = new LocalAudioStore(config);
            repository = FileCallRecordRepository.open(config, clock);
            CallRecordService service = new CallRecordService(
                    repository, audioStore, contactGroups, config, clock);
            TranscriptionWorker.Transcriber transcriber = providedTranscriber;
            if (transcriber == null) {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(config.funAsrConnectTimeout())
                        .build();
                FunAsrClient funAsr = new FunAsrClient(config, client);
                transcriber = funAsr::transcribe;
            }
            repository.recoverProcessing(clock.instant());
            worker = new TranscriptionWorker(
                    repository, audioStore, transcriber, config, clock, events);
            worker.start();
            return new CallRecordRuntime(
                    repository, worker, service, audioStore, null);
        } catch (NoClassDefFoundError error) {
            CallRecordException failure = new CallRecordException(
                    "CALL_RECORD_DEPENDENCY_MISSING", 503,
                    "电话记录依赖未加载，请使用完整发布包启动", false, error);
            closePartial(worker, repository, failure);
            return new CallRecordRuntime(null, null, null, null, failure);
        } catch (Exception exception) {
            CallRecordException failure = startupFailure(exception);
            closePartial(worker, repository, failure);
            return new CallRecordRuntime(null, null, null, null, failure);
        }
    }

    public boolean available() {
        return startupFailure == null && !closing && !closed;
    }

    public CallRecordService service() {
        return service;
    }

    public LocalAudioStore audioStore() {
        return audioStore;
    }

    public CallRecordException startupFailure() {
        return startupFailure;
    }

    @Override
    public synchronized void close() throws CallRecordException {
        if (closed) return;
        closing = true;
        if (worker != null) {
            try {
                worker.close();
            } catch (CallRecordException exception) {
                // Keep the repository lock while any old worker may still write.
                throw exception;
            }
        }
        CallRecordException failure = null;
        if (repository != null) {
            try {
                repository.close();
            } catch (CallRecordException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        closed = true;
        if (failure != null) throw failure;
    }

    private static CallRecordException startupFailure(Exception exception) {
        if (exception instanceof CallRecordException structured) return structured;
        return new CallRecordException(
                "CALL_RECORD_STARTUP_FAILED", 503,
                "Call record subsystem could not start", false, exception);
    }

    private static void closePartial(TranscriptionWorker worker,
                                     FileCallRecordRepository repository,
                                     CallRecordException primary) {
        if (worker != null) {
            try {
                worker.close();
            } catch (CallRecordException exception) {
                primary.addSuppressed(exception);
            }
        }
        if (repository != null) {
            try {
                repository.close();
            } catch (CallRecordException exception) {
                primary.addSuppressed(exception);
            }
        }
    }
}
