package com.relay.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class ManagementTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;
    public ManagementTokenFilter(String token) { expected=digest(token); }
    private static byte[] digest(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static boolean managementPath(String path) {
        return path.matches("/(workflows|runs|approvals)(/.*)?");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !managementPath(request.getServletPath());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var headers=Collections.list(request.getHeaders("Authorization"));
        if (headers.size()!=1 || !headers.getFirst().matches("(?i:Bearer) [A-Za-z0-9\\-._~+/]+=*")) {
            deny(response,401);return;
        }
        String token=headers.getFirst().substring(7);
        if (!MessageDigest.isEqual(expected,digest(token))) { deny(response,401);return; }
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                ManagementAuthentication.PRINCIPAL,null,List.of(new SimpleGrantedAuthority(ManagementAuthentication.AUTHORITY))));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request,response); }
        finally { SecurityContextHolder.clearContext(); }
    }
    public static void deny(HttpServletResponse response,int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control","no-store");
        if (status==401) response.setHeader("WWW-Authenticate","Bearer realm=\"Relay\"");
        response.getWriter().write(status==401
            ? "{\"error\":{\"message\":\"Management authentication is required.\",\"code\":\"unauthorized\"}}"
            : "{\"error\":{\"message\":\"Access is denied.\",\"code\":\"forbidden\"}}");
    }
}
