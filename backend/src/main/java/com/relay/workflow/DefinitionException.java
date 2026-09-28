package com.relay.workflow;

/** Only fixed reasons and structural paths; never retain raw parser exceptions or values. */
public final class DefinitionException extends IllegalArgumentException {
    private final String reason;
    private final String path;
    public DefinitionException(String reason, String path) {
        super("Invalid workflow definition: " + reason + " at " + path);
        this.reason=reason; this.path=path;
    }
    public String reason() { return reason; }
    public String path() { return path; }
}
