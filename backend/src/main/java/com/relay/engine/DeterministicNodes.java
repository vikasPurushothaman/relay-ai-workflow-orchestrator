package com.relay.engine;

import java.math.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class DeterministicNodes {
    private DeterministicNodes() {}
    public static boolean condition(JsonNode p) {
        String left=p.get("left").asString(),right=p.get("right").asString();
        return switch(p.get("op").asString()) {
            case "equals" -> left.equals(right);
            case "not_equals" -> !left.equals(right);
            case "contains" -> left.contains(right);
            case "greater_than" -> decimal(left).compareTo(decimal(right))>0;
            case "less_than" -> decimal(left).compareTo(decimal(right))<0;
            default -> throw new NodeFailure("invalid_condition");
        };
    }
    private static BigDecimal decimal(String value) {
        String v=value.strip();
        if(!v.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new NodeFailure("invalid_numeric_operand");
        try{return new BigDecimal(v);}catch(NumberFormatException e){throw new NodeFailure("invalid_numeric_operand");}
    }
    public static Instant delay(Instant now,JsonNode seconds) {
        try {
            var value=new BigDecimal(seconds.asString());
            if(value.signum()<0)throw new ArithmeticException();
            long ms=value.multiply(BigDecimal.valueOf(1000)).setScale(0,RoundingMode.CEILING).longValueExact();
            if(ms<0)throw new ArithmeticException();
            return date(now.plusMillis(ms));
        } catch(RuntimeException ex){throw new NodeFailure("invalid_delay");}
    }
    public static Instant date(Instant time) {
        if(time.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z")))throw new NodeFailure("deadline_out_of_range");
        return time;
    }
    public static ObjectNode request(String type,JsonNode p,String world,DestinationPolicy destinations) {
        var request=EngineJson.JSON.createObjectNode();
        var headers=EngineJson.JSON.createObjectNode();
        JsonNode body=null;String method="POST",url;
        switch(type) {
            case "http_request" -> {
                method=p.get("method").asString();url=p.get("url").asString();
                if(!Set.of("GET","POST","PUT","DELETE").contains(method))throw new NodeFailure("invalid_method");
                if(p.has("headers")) {
                    var seen=new java.util.HashSet<String>();
                    for(String name:p.get("headers").propertyNames()) {
                        String lower=name.toLowerCase(java.util.Locale.ROOT);
                        var value=p.get("headers").get(name);
                        if(!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || !value.isString()
                            || value.asString().chars().anyMatch(c->c<32 || c==127)
                            || !seen.add(lower) || Set.of("idempotency-key","host","content-length","connection","transfer-encoding","upgrade","expect","authorization","cookie","proxy-authorization").contains(lower))
                            throw new NodeFailure("invalid_header");
                        headers.put(name,value.asString());
                    }
                }
                body=p.get("body");
            }
            case "notify" -> {
                if(p.get("to").asString().isBlank())throw new NodeFailure("invalid_recipient");
                var data=EngineJson.JSON.createObjectNode().put("message",p.get("message").asString());
                if(p.get("channel").asString().equals("email")) {
                    url=world+"/email/send";data.put("to",p.get("to").asString());
                    if(p.has("subject"))data.put("subject",p.get("subject").asString());
                } else {url=world+"/chat/message";data.put("channel",p.get("to").asString());}
                body=data;
            }
            case "order_action" -> {
                String id=p.get("order_id").asString();if(id.isBlank())throw new NodeFailure("invalid_order_id");
                url=world+"/orders/"+URLEncoder.encode(id,StandardCharsets.UTF_8).replace("+","%20")+"/"+p.get("action").asString();
                var data=EngineJson.JSON.createObjectNode();if(p.has("amount_usd"))data.set("amount_usd",p.get("amount_usd"));body=data;
            }
            default -> throw new NodeFailure("node_not_implemented");
        }
        destinations.check(url);
        request.put("method",method).put("url",url).set("headers",headers);
        request.put("body",body==null?"":TemplateResolver.canonical(body));
        request.put("type",type);
        return request;
    }
}
