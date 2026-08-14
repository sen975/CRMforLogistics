package com.crmforlogistics.messagecenter.service.wecom;

public interface ViewerAuditSink {
    void record(String action, String result, String wecomUserId,
                String contactPointId, String viewerSessionId);

    default void recordDiagnostic(String action, String result, String wecomUserId,
                                  String contactPointId, String viewerSessionId,
                                  String errorCode, Integer upstreamErrcode,
                                  String upstreamPath) {
        recordDiagnostic(action, result, wecomUserId, contactPointId, viewerSessionId,
                errorCode, upstreamErrcode, upstreamPath, null, null);
    }

    default void recordDiagnostic(String action, String result, String wecomUserId,
                                  String contactPointId, String viewerSessionId,
                                  String errorCode, Integer upstreamErrcode,
                                  String upstreamPath, Integer upstreamHttpStatus) {
        recordDiagnostic(action, result, wecomUserId, contactPointId, viewerSessionId,
                errorCode, upstreamErrcode, upstreamPath, upstreamHttpStatus, null);
    }

    void recordDiagnostic(String action, String result, String wecomUserId,
                          String contactPointId, String viewerSessionId,
                          String errorCode, Integer upstreamErrcode,
                          String upstreamPath, Integer upstreamHttpStatus,
                          String upstreamHint);
}
