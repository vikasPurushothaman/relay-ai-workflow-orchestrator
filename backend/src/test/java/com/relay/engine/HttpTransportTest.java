package com.relay.engine;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HttpTransportTest {
    EngineStore.Prepared prepared(String url,String type,int timeout) {
        var request=EngineJson.JSON.createObjectNode().put("url",url).put("method","POST").put("body","{\"to\":\"x\",\"message\":\"hi\"}").put("type",type);
        request.set("headers",EngineJson.JSON.createObjectNode());
        return new EngineStore.Prepared(new EngineStore.Claim("r","owner",1,"a",1L),1,request,"r:1",new ExecutionPolicy(60000,10000,5000,timeout,30000,3,1,2));
    }
    @Test void transportHasSingleSendStableBodyBoundedReadAndRetryClassification() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var threads=Executors.newCachedThreadPool();server.setExecutor(threads);
        var mode=new AtomicInteger(200);var calls=new AtomicInteger();var key=new AtomicReference<String>();var body=new AtomicReference<String>();
        server.createContext("/",exchange->{
            calls.incrementAndGet();key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            try {
                int status=mode.get();
                if(status==0){exchange.close();return;}
                byte[] bytes=(status==201?"not-json":status==202?"[1]":"{\"delivered\":true,\"notification_id\":\"n\"}").getBytes();
                if(status==299)bytes=new byte[EngineJson.MAX_BYTES+1];
                exchange.getResponseHeaders().add("Retry-After","3");exchange.getResponseHeaders().add("Location","/redirected");
                exchange.sendResponseHeaders(status,bytes.length);
                if(status==298)try{Thread.sleep(600);}catch(InterruptedException ignored){}
                exchange.getResponseBody().write(bytes);
            }catch(java.io.IOException ignored){}finally{exchange.close();}
        });server.start();
        String origin="http://127.0.0.1:"+server.getAddress().getPort();var policy=new DestinationPolicy(origin);var transport=new HttpTransport();
        try {
            var p=prepared(origin+"/email/send","notify",2000);
            assertThat(transport.send(p,policy).success()).isTrue();assertThat(calls.get()).isEqualTo(1);assertThat(key.get()).isEqualTo("r:1");assertThat(body.get()).isEqualTo(p.request().get("body").asString());
            for(int status:new int[]{408,429,500,502,503,504,400,401,404,409,302}) {
                mode.set(status);int before=calls.get();var result=transport.send(p,policy);
                assertThat(result.success()).isFalse();assertThat(result.retryable()).isEqualTo(java.util.Set.of(408,429,500,502,503,504).contains(status));assertThat(calls.get()).isEqualTo(before+1);
            }
            mode.set(201);assertThat(transport.send(p,policy).code()).isEqualTo("invalid_adapter_response");
            mode.set(202);var generic=prepared(origin+"/x","http_request",2000);assertThat(transport.send(generic,policy).output().get("body").asString()).isEqualTo("[1]");
            mode.set(299);assertThat(transport.send(p,policy).code()).isEqualTo("response_too_large");
            mode.set(298);long start=System.nanoTime();var timeout=transport.send(prepared(origin+"/slow","http_request",100),policy);
            assertThat(timeout.success()).isFalse();assertThat(timeout.retryable()).isTrue();assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
            mode.set(0);int before=calls.get();assertThat(transport.send(p,policy).success()).isFalse();assertThat(calls.get()).isEqualTo(before+1);
        }finally{server.stop(0);threads.shutdownNow();}
    }
    @Test void openAiBillingErrorsAreSafeAndPermanentButRateLimitsStillRetry()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var body=new AtomicReference<>("{}");var calls=new AtomicInteger();var responseStatus=new AtomicInteger(429);
        server.createContext("/",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After","7");exchange.sendResponseHeaders(responseStatus.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            String origin="http://127.0.0.1:"+server.getAddress().getPort();var destinations=new DestinationPolicy(origin);var transport=new HttpTransport();
            var p=prepared(origin,"ai",2000);((tools.jackson.databind.node.ObjectNode)p.request()).put("provider","openai");
            for(String code:new String[]{"credit_balance_exhausted","organization_spend_limit_exceeded","project_spend_limit_exceeded","organization_usage_limit_exceeded","insufficient_quota"}) {
                body.set("{\"error\":{\"code\":\""+code+"\",\"type\":\"insufficient_quota\",\"message\":\"private-key-sentinel customer-prompt\"}}");
                int before=calls.get();var result=transport.sendAuthenticated(p,destinations,"fake-key");
                assertThat(result.code()).isEqualTo("ai_"+code);assertThat(result.retryable()).isFalse();assertThat(result.retryAfter()).isNull();assertThat(result.output()).isNull();assertThat(calls.get()).isEqualTo(before+1);
                assertThat(result.toString()).doesNotContain("private-key-sentinel","customer-prompt","fake-key");
            }
            body.set("{\"error\":{\"type\":\"insufficient_quota\",\"code\":null}}");assertThat(transport.send(p,destinations).code()).isEqualTo("ai_insufficient_quota");
            for(String invalid:new String[]{"not JSON","null","{}","{\"error\":{\"code\":\"slow_down\",\"type\":\"rate_limit_error\"}}","{\"error\":{\"code\":\"attacker-secret\"}}","{\"error\":{\"code\":\"credit_balance_exhausted\",\"code\":\"slow_down\"}}"}) {
                body.set(invalid);var result=transport.send(p,destinations);assertThat(result.code()).isEqualTo("http_429");assertThat(result.retryable()).isTrue();assertThat(result.retryAfter()).isEqualTo("7");
            }
            body.set("{\"error\":{\"code\":\"credit_balance_exhausted\"}}");
            ((tools.jackson.databind.node.ObjectNode)p.request()).put("provider","mock-http");assertThat(transport.send(p,destinations).code()).isEqualTo("http_429");
            assertThat(transport.send(prepared(origin,"http_request",2000),destinations).code()).isEqualTo("http_429");
            ((tools.jackson.databind.node.ObjectNode)p.request()).put("provider","openrouter");responseStatus.set(402);
            var payment=transport.send(p,destinations);assertThat(payment.code()).isEqualTo("ai_payment_required");assertThat(payment.retryable()).isFalse();assertThat(payment.output()).isNull();assertThat(payment.retryAfter()).isNull();
            responseStatus.set(429);var limited=transport.send(p,destinations);assertThat(limited.code()).isEqualTo("http_429");assertThat(limited.retryable()).isTrue();
            responseStatus.set(402);assertThat(transport.send(prepared(origin,"http_request",2000),destinations).code()).isEqualTo("http_402");
        }finally{server.stop(0);}
    }

}
