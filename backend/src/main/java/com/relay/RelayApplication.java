package com.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RelayApplication {
    public static void main(String[] args) {
        // Persisted attempt accounting owns retries; disable JDK transport resends before initialization.
        System.setProperty("jdk.httpclient.disableRetryConnect","true");
        System.setProperty("jdk.httpclient.enableAllMethodRetry","false");
        System.setProperty("jdk.httpclient.redirects.retrylimit","1");
        SpringApplication.run(RelayApplication.class, args);
    }
}
