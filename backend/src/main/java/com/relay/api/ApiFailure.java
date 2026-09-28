package com.relay.api;

/** Fixed messages prevent raw exception/constraint/payload details reaching clients. */
public final class ApiFailure extends RuntimeException {
    public enum Reason {
        WEBHOOK_AUTH(401,"invalid_webhook_secret","A valid workflow secret is required."),
        NOT_PUBLISHED(409,"workflow_not_published","The workflow must be published before starting a run."),
        INVALID_TRIGGER(409,"invalid_trigger","The workflow does not support this trigger."),
        TOO_LARGE(413,"payload_too_large","The request exceeds the size limit."),
        NOT_FOUND(404,"not_found","The requested resource was not found."),
        CONFLICT(409,"conflict","The resource state conflicts with this request."),
        INVALID_INPUT(400,"invalid_input","The request is invalid.");
        final int status;
        final String code;
        final String message;
        Reason(int status,String code,String message) { this.status=status;this.code=code;this.message=message; }
    }
    private final Reason reason;
    public ApiFailure(Reason reason) { super(reason.message);this.reason=reason; }
    public Reason reason() { return reason; }
}
