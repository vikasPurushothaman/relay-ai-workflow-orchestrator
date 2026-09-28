package com.relay.workflow;

import java.util.*;
import java.util.regex.Pattern;

/** Parses references only. No evaluation, substitution or runtime data access. */
public final class TemplateSyntax {
    private TemplateSyntax() {}
    private static final Pattern TRIGGER=Pattern.compile("trigger\\.body(?:\\.[A-Za-z0-9_-]+)*");
    private static final Pattern NODE=Pattern.compile("nodes\\.([A-Za-z_][A-Za-z0-9_-]{0,127})\\.output(?:\\.[A-Za-z0-9_-]+)*");
    public record Reference(String expression,String nodeId) {}
    public static boolean hasDelimiters(String value) { return value.contains("{{") || value.contains("}}"); }
    public static List<Reference> parse(String value,Set<String> nodes,String path) {
        var references=new ArrayList<Reference>();
        int cursor=0;
        while(cursor<value.length()) {
            int open=value.indexOf("{{",cursor), close=value.indexOf("}}",cursor);
            if(open<0 && close<0) break;
            if(open<0 || close<open) throw new DefinitionException("invalid_template",path);
            String expression=value.substring(open+2,close).strip();
            var node=NODE.matcher(expression);
            if(node.matches()) {
                if(!nodes.contains(node.group(1))) throw new DefinitionException("unknown_template_node",path);
                references.add(new Reference(expression,node.group(1)));
            } else if(TRIGGER.matcher(expression).matches()) references.add(new Reference(expression,null));
            else throw new DefinitionException("invalid_template",path);
            cursor=close+2;
            if(cursor<value.length() && value.charAt(cursor)=='}') throw new DefinitionException("invalid_template",path);
        }
        return List.copyOf(references);
    }
}
