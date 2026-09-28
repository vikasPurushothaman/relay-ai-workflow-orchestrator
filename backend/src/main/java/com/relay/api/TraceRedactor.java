package com.relay.api;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

/** Copies trace JSON with credential-like values removed. Never mutates its input. */
public final class TraceRedactor {
    public static final String MARK="[REDACTED]";
    private static final Pattern SENSITIVE=Pattern.compile("authorization|secret|token|password|passwd|apikey|cookie|credential|privatekey|signature");
    private final String secret;
    public static final int MIN_VALUE_SECRET=8;
    /**
     * @param secret the run's webhook secret, replaced wherever it appears in strings. Shorter secrets are skipped
     * because replacing a few common characters would destroy the trace; key-name rules still apply.
     */
    public TraceRedactor(String secret){this.secret=secret==null || secret.length()<MIN_VALUE_SECRET?null:secret;}

    public static boolean sensitive(String name) {
        return SENSITIVE.matcher(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","")).find();
    }
    public JsonNode redact(JsonNode node) {
        if(node==null)return null;
        if(node instanceof ObjectNode object) {
            var copy=JsonNodeFactory.instance.objectNode();
            for(var name:object.propertyNames())copy.set(name,sensitive(name)?JsonNodeFactory.instance.stringNode(MARK):redact(object.get(name)));
            return copy;
        }
        if(node instanceof ArrayNode array) {
            var copy=JsonNodeFactory.instance.arrayNode();
            for(var item:array)copy.add(redact(item));
            return copy;
        }
        if(node.isString())return JsonNodeFactory.instance.stringNode(text(node.asString()));
        return node.deepCopy();
    }
    public String text(String value) {
        if(value==null)return null;
        String out=secret==null?value:value.replace(secret,MARK);
        return url(out);
    }
    /** Absolute http(s) URLs lose user-info and sensitive query values; other strings pass through. */
    static String url(String value) {
        if(!value.regionMatches(true,0,"http://",0,7) && !value.regionMatches(true,0,"https://",0,8))return value;
        URI uri;
        try{uri=new URI(value);}catch(Exception e){return value;}
        if(uri.getRawAuthority()==null)return value;
        String authority=uri.getRawAuthority(),query=uri.getRawQuery();
        boolean changed=false;
        int at=authority.lastIndexOf('@');
        if(at>=0){authority=MARK+"@"+authority.substring(at+1);changed=true;}
        if(query!=null) {
            var parts=query.split("&",-1);
            for(int i=0;i<parts.length;i++) {
                int eq=parts[i].indexOf('=');
                String name=eq<0?parts[i]:parts[i].substring(0,eq);
                String decoded;
                try{decoded=java.net.URLDecoder.decode(name,java.nio.charset.StandardCharsets.UTF_8);}catch(IllegalArgumentException e){decoded=name;}
                if(eq>=0 && sensitive(decoded)){parts[i]=name+"="+MARK;changed=true;}
            }
            query=String.join("&",parts);
        }
        if(!changed)return value;
        return uri.getScheme()+"://"+authority+(uri.getRawPath()==null?"":uri.getRawPath())+(query==null?"":"?"+query)+(uri.getRawFragment()==null?"":"#"+uri.getRawFragment());
    }
}
