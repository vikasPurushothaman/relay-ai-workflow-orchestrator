package com.relay.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.CorsFilter;

public final class ManagementCors {
    private ManagementCors() {}
    public static List<String> origins(String value) {
        var result=new ArrayList<String>();
        if (value.isBlank()) return List.of();
        try {
            for(String item:value.split(",",-1)) {
                String origin=item.trim();
                URI uri=URI.create(origin);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost()==null
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || !uri.getRawPath().isEmpty() || uri.getPort()==0 || uri.getPort()>65535) throw new IllegalArgumentException();
                if (!result.contains(origin)) result.add(origin);
            }
        } catch(IllegalArgumentException e) {
            throw new IllegalStateException("RELAY_ALLOWED_ORIGINS must be comma-separated HTTP(S) origins without paths, credentials or wildcards");
        }
        return List.copyOf(result);
    }
    public static CorsFilter filter(String configured) {
        var config=new CorsConfiguration();
        config.setAllowedOrigins(origins(configured));
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type","Accept"));
        config.setExposedHeaders(List.of("WWW-Authenticate","Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(600L);
        var filter=new CorsFilter(request -> ManagementTokenFilter.managementPath(request.getServletPath())?config:null);
        filter.setCorsProcessor(new DefaultCorsProcessor() {
            @Override protected void rejectRequest(ServerHttpResponse response) throws java.io.IOException {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                response.getHeaders().setCacheControl("no-store");
                response.getBody().write("{\"error\":{\"message\":\"Browser origin or preflight is not allowed.\",\"code\":\"cors_denied\"}}".getBytes(StandardCharsets.UTF_8));
                response.flush();
            }
        });
        return filter;
    }
}
