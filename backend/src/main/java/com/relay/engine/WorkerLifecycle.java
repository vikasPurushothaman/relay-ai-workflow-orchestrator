package com.relay.engine;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** One dispatch thread, one heartbeat thread. Deployment must stop the old process before replacement. */
@Component
@ConditionalOnProperty(name="relay.launch.mode",havingValue="worker")
public class WorkerLifecycle implements SmartLifecycle {
    private final EngineStore store;
    private final HttpTransport transport;
    private final com.relay.ai.AiProvider ai;
    private final DestinationPolicy destinations;
    private final int pollMs;
    private final String owner=UUID.randomUUID().toString();
    private volatile boolean running;
    private ExecutorService dispatcher;
    private ScheduledExecutorService heartbeat;
    public WorkerLifecycle(EngineStore store,HttpTransport transport,Environment env,com.relay.ai.AiProvider ai) {
        this.ai=ai;
        this.store=store;this.transport=transport;
        String world=env.getProperty("MOCK_WORLD_URL","http://localhost:9210");
        destinations=new DestinationPolicy(env.getProperty("RELAY_HTTP_ALLOWED_ORIGINS",world));
        pollMs=ExecutionPolicy.value(env,"RELAY_JOB_POLL_MS",250);
        ExecutionPolicy.from(env);
    }
    public boolean isRunning(){return running;}
    public synchronized void start() {
        if(running)return;
        running=true;dispatcher=Executors.newSingleThreadExecutor();heartbeat=Executors.newSingleThreadScheduledExecutor();
        dispatcher.submit(()->{
            boolean expiredFirst=false;
            while(running) {
                boolean found=false;
                try {
                    for(String id:store.candidates(expiredFirst=!expiredFirst)) {
                        if(!running)break;
                        try {
                        var claim=store.claim(id,owner);if(claim==null)continue;found=true;
                        var prepared=store.prepare(claim);if(prepared==null)continue;
                        if(!store.renew(prepared.claim()))continue;
                        var ownership=new AtomicBoolean(true);
                        var active=new AtomicBoolean(true);
                        var renewalLock=new Object();
                        Thread dispatchThread=Thread.currentThread();
                        var renewal=heartbeat.scheduleWithFixedDelay(()->{
                            synchronized(renewalLock) {
                                if(!active.get())return;
                                try {if(store.renewalState(prepared.claim())!=0)return;}catch(RuntimeException ignored){}
                                ownership.set(false);dispatchThread.interrupt();
                            }
                        },prepared.policy().renewMs(),prepared.policy().renewMs(),TimeUnit.MILLISECONDS);
                        try {
                            var outcome=prepared.request().path("type").asString().equals("ai")?ai.invoke(prepared):transport.send(prepared,destinations);
                            if(ownership.get() && running)store.finish(prepared,outcome);
                        } finally {
                            renewal.cancel(false);
                            synchronized(renewalLock){active.set(false);Thread.interrupted();}
                        }
                        } catch(EngineStore.LostLease ignored) {
                            // This candidate belongs to another generation; continue other work.
                        } catch(RuntimeException error) {
                            LoggerFactory.getLogger(WorkerLifecycle.class).warn("Job could not dispatch; inspect stored policy/state ({})",error.getClass().getSimpleName());
                        }
                    }
                } catch(EngineStore.LostLease ignored) {
                    // Another generation or expiry owns reconciliation; never resend here.
                } catch(RuntimeException error) {
                    LoggerFactory.getLogger(WorkerLifecycle.class).warn("Worker transaction failed; durable state will be reconciled ({})",error.getClass().getSimpleName());
                }
                if(!found)try{Thread.sleep(pollMs);}catch(InterruptedException ignored){}
            }
        });
    }
    public synchronized void stop() {
        running=false;
        if(heartbeat!=null)heartbeat.shutdownNow();
        if(dispatcher!=null){dispatcher.shutdownNow();try{dispatcher.awaitTermination(60,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
}
