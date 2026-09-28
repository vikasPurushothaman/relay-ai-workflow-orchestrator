package com.relay.engine;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class HttpTransport {
    public record Outcome(JsonNode output,String code,boolean retryable,String retryAfter,Long tokensPrompt,Long tokensCompletion) {
        public Outcome(JsonNode output,String code,boolean retryable,String retryAfter){this(output,code,retryable,retryAfter,null,null);}
        public boolean success(){return code==null;}
        public Instant retryAt(Instant now) {
            if(retryAfter==null)return null;
            try {
                if(retryAfter.matches("[0-9]+"))return now.plusSeconds(Long.parseLong(retryAfter));
                Instant at=ZonedDateTime.parse(retryAfter,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return at.isAfter(now)?at:null;
            }catch(RuntimeException ex){return retryAfter.matches("[0-9]+")?Instant.MAX:null;}
        }
        public static Outcome failed(String code,boolean retry){return new Outcome(null,code,retry,null);}
    }
    public Outcome send(EngineStore.Prepared p,DestinationPolicy destinations) {
        return sendAuthenticated(p,destinations,null);
    }
    public Outcome sendAuthenticated(EngineStore.Prepared p,DestinationPolicy destinations,String credential) {
        var request=p.request();
        int timeout=request.path("type").asString().equals("ai")?p.policy().aiMs():p.policy().httpMs();
        HttpClient client=null;CompletableFuture<HttpResponse<byte[]>> future=null;
        try {
            var uri=destinations.check(request.get("url").asString());
            client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
            var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout));
            var headers=request.get("headers");
            boolean contentType=false;
            for(String key:headers.propertyNames()){builder.header(key,headers.get(key).asString());contentType|=key.equalsIgnoreCase("Content-Type");}
            if(credential!=null)builder.header("Authorization","Bearer "+credential);
            if(p.key()!=null)builder.header("Idempotency-Key",p.key());
            if(!contentType)builder.header("Content-Type","application/json");
            String body=request.get("body").asString();
            builder.method(request.get("method").asString(),body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
            future=client.sendAsync(builder.build(),ignored->new LimitedBody());
            var response=future.get(timeout,TimeUnit.MILLISECONDS);
            int status=response.statusCode();
            if(status==402 && request.path("type").asString().equals("ai") && request.path("provider").asString().equals("openrouter"))return Outcome.failed("ai_payment_required",false);
            if(status==429 && request.path("type").asString().equals("ai") && request.path("provider").asString().equals("openai")) {
                String billing=billingFailure(response.body());
                if(billing!=null)return Outcome.failed(billing,false);
            }
            if(status<200 || status>=300)return new Outcome(null,"http_"+status,Set.of(408,429,500,502,503,504).contains(status),
                status==429 || status==503?response.headers().firstValue("Retry-After").orElse(null):null);
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            JsonNode parsed=null;
            try{parsed=EngineJson.read(text);}catch(RuntimeException ignored){}
            String type=request.get("type").asString();
            if(type.equals("ai"))return parsed!=null && parsed.isObject()?new Outcome(parsed,null,false,null):Outcome.failed("invalid_provider_response",false);
            if(type.equals("http_request")) {
                var output=EngineJson.JSON.createObjectNode().put("status",status);
                if(parsed!=null && parsed.isObject())output.set("body",parsed);else output.put("body",text);
                return new Outcome(output,null,false,null);
            }
            if(parsed==null || !parsed.isObject())return Outcome.failed("invalid_adapter_response",false);
            if(type.equals("notify")) {
                if(!parsed.path("delivered").isBoolean() || !parsed.path("notification_id").isString())return Outcome.failed("invalid_adapter_response",false);
                return new Outcome(EngineJson.JSON.createObjectNode().put("delivered",parsed.get("delivered").asBoolean()).put("notification_id",parsed.get("notification_id").asString()),null,false,null);
            }
            if(!parsed.path("status").isString() || !parsed.path("reference_id").isString())return Outcome.failed("invalid_adapter_response",false);
            return new Outcome(EngineJson.JSON.createObjectNode().put("status",parsed.get("status").asString()).put("reference_id",parsed.get("reference_id").asString()),null,false,null);
        } catch(NodeFailure ex){return Outcome.failed(ex.code,false);}
        catch(TimeoutException ex){return Outcome.failed("http_timeout",true);}
        catch(InterruptedException ex){Thread.currentThread().interrupt();return Outcome.failed("transport_interrupted",true);}
        catch(ExecutionException ex) {
            boolean invalidBody=false,tls=false,timedOut=false;
            for(Throwable cause=ex;cause!=null;cause=cause.getCause()) {
                invalidBody|=cause instanceof NodeFailure;
                tls|=cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException;
                timedOut|=cause instanceof HttpTimeoutException;
            }
            return Outcome.failed(invalidBody?"response_too_large":tls?"tls_failure":timedOut?"http_timeout":"transport_failure",!invalidBody && !tls);
        } catch(Exception ex){return Outcome.failed("invalid_request_or_response",false);}
        finally {
            if(future!=null && !future.isDone())future.cancel(true);
            if(client!=null){client.shutdownNow();client.close();}
        }
    }
    /** Only fixed codes escape the bounded provider body; messages may contain secrets or input. */
    private static String billingFailure(byte[] bytes) {
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var error=EngineJson.read(text).path("error");
            String code=error.path("code").asString();
            if(Set.of("credit_balance_exhausted","organization_spend_limit_exceeded","project_spend_limit_exceeded","organization_usage_limit_exceeded","insufficient_quota").contains(code))return "ai_"+code;
            if(error.path("type").asString().equals("insufficient_quota"))return "ai_insufficient_quota";
        }catch(RuntimeException | java.nio.charset.CharacterCodingException ignored){}
        return null;
    }
    /** Bound memory during body receipt, not after buffering an unlimited response. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if((long)bytes.size()+buffer.remaining()>EngineJson.MAX_BYTES){subscription.cancel();result.completeExceptionally(new NodeFailure("response_too_large"));return;}
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
