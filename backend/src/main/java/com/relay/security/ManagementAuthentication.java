package com.relay.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class ManagementAuthentication {
    public static final String PRINCIPAL = "demo-operator";
    public static final String AUTHORITY = "RELAY_MANAGEMENT";
    private ManagementAuthentication() {}
    public static String requireActor() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication==null || !authentication.isAuthenticated()
                || !PRINCIPAL.equals(authentication.getName())
                || authentication.getAuthorities().stream().noneMatch(a -> AUTHORITY.equals(a.getAuthority()))) {
            throw new org.springframework.security.access.AccessDeniedException("Management authentication required");
        }
        return PRINCIPAL;
    }
}
