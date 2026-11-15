package com.hardwarebilling.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force protection for the login form: after N failures for an IP address or a
 * username, further login attempts are refused for a cool-down period. (Single-instance deployment.)
 */
@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private record Attempts(int count, Instant lockedUntil, Instant last) {}

    private final ConcurrentHashMap<String, Attempts> map = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration lock;

    public LoginRateLimitFilter(AppProperties props) {
        this.maxFailures = props.login() == null || props.login().maxFailures() <= 0 ? 5 : props.login().maxFailures();
        this.lock = Duration.ofMinutes(props.login() == null || props.login().lockMinutes() <= 0 ? 15 : props.login().lockMinutes());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getMethod().equals("POST") && request.getServletPath().equals("/login"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String user = req.getParameter("username");
        if (locked("ip:" + req.getRemoteAddr()) || (user != null && locked("u:" + user.toLowerCase(Locale.ROOT)))) {
            res.sendRedirect(req.getContextPath() + "/login?locked");
            return;
        }
        chain.doFilter(req, res);
    }

    public boolean locked(String key) {
        Attempts a = map.get(key);
        return a != null && a.lockedUntil() != null && Instant.now().isBefore(a.lockedUntil());
    }

    void failure(String key) {
        Instant now = Instant.now();
        map.merge(key, new Attempts(1, null, now), (old, n) -> {
            boolean expired = (old.lockedUntil() != null && now.isAfter(old.lockedUntil()))
                    || (old.lockedUntil() == null && old.last().isBefore(now.minus(lock)));   // old, isolated typos do not add up
            int c = expired ? 1 : old.count() + 1;
            return new Attempts(c, c >= maxFailures ? now.plus(lock) : null, now);
        });
        if (map.size() > 10_000) map.entrySet().removeIf(e -> e.getValue().last().isBefore(now.minus(lock)));
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent e) {
        if (e.getAuthentication().getDetails() instanceof WebAuthenticationDetails d) failure("ip:" + d.getRemoteAddress());
        failure("u:" + String.valueOf(e.getAuthentication().getName()).toLowerCase(Locale.ROOT));
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent e) {
        map.remove("u:" + String.valueOf(e.getAuthentication().getName()).toLowerCase(Locale.ROOT));
        if (e.getAuthentication().getDetails() instanceof WebAuthenticationDetails d) map.remove("ip:" + d.getRemoteAddress());
    }
}
