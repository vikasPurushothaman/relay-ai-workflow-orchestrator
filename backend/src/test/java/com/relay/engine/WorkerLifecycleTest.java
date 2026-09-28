package com.relay.engine;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Regression (task 8.6, D-8.6-2): the dispatch loop must never die silently and leave a live, idle worker. */
class WorkerLifecycleTest {
    private WorkerLifecycle worker(EngineStore store) {
        return new WorkerLifecycle(store,mock(HttpTransport.class),
            new MockEnvironment().withProperty("RELAY_JOB_POLL_MS","5"),mock(com.relay.ai.AiProvider.class));
    }

    @Test void unexpectedErrorEndsWorkerThroughFatalExitHook() throws Exception {
        var store=mock(EngineStore.class);
        when(store.candidates(anyBoolean())).thenThrow(new NoClassDefFoundError("simulated replaced jar"));
        var worker=worker(store);var exits=new AtomicInteger();var exited=new CountDownLatch(1);
        worker.fatalExit=()->{exits.incrementAndGet();exited.countDown();};
        worker.start();
        try {
            assertThat(exited.await(5,TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            assertThat(exits.get()).isEqualTo(1);
            assertThat(worker.isRunning()).isFalse();
            verify(store,times(1)).candidates(anyBoolean());
        } finally { worker.stop(); }
        assertThat(exits.get()).isEqualTo(1);
    }

    @Test void runtimeFailureKeepsPollingWithoutExit() throws Exception {
        var store=mock(EngineStore.class);var calls=new CountDownLatch(3);
        when(store.candidates(anyBoolean())).thenAnswer(invocation->{calls.countDown();
            if(calls.getCount()==2)throw new IllegalStateException("transient database failure");return List.of();});
        var worker=worker(store);var exits=new AtomicInteger();worker.fatalExit=exits::incrementAndGet;
        worker.start();
        try {
            assertThat(calls.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(worker.isRunning()).isTrue();
        } finally { worker.stop(); }
        assertThat(exits.get()).isZero();
    }

    @Test void normalStopNeverCallsFatalExit() throws Exception {
        var store=mock(EngineStore.class);var polled=new CountDownLatch(1);
        when(store.candidates(anyBoolean())).thenAnswer(invocation->{polled.countDown();return List.of();});
        var worker=worker(store);var exits=new AtomicInteger();worker.fatalExit=exits::incrementAndGet;
        worker.start();
        assertThat(polled.await(5,TimeUnit.SECONDS)).isTrue();
        worker.stop();
        assertThat(worker.isRunning()).isFalse();
        assertThat(exits.get()).isZero();
    }
}
