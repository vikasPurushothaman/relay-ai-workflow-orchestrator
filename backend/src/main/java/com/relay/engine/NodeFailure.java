package com.relay.engine;

/** Codes only: do not persist exception strings containing headers, bodies or credentials. */
public class NodeFailure extends RuntimeException {
    public final String code;
    public NodeFailure(String code){super(code);this.code=code;}
}
