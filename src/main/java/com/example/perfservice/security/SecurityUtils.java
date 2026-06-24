package com.example.perfservice.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtils {

    private SecurityUtils() {}

    /**
     * Returns the userId of the currently authenticated user from the SecurityContext.
     * The principal is set by JwtAuthFilter as a Long (userId from JWT claim).
     */
    public static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof Long id) return id;
        return null;
    }

    public static Long requireUserId() {
        Long id = currentUserId();
        if (id == null) throw new IllegalStateException("No authenticated user in context");
        return id;
    }
}