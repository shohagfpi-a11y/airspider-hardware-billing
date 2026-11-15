package com.hardwarebilling.config;

import com.hardwarebilling.service.SetupService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Until the administrator exists, every page redirects to /setup. */
@Component
public class SetupRedirectFilter extends OncePerRequestFilter {
    private final SetupService setup;

    public SetupRedirectFilter(SetupService setup) {
        this.setup = setup;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        String p = r.getServletPath();
        return p.startsWith("/setup") || p.startsWith("/css/") || p.startsWith("/js/") || p.startsWith("/webjars/")
                || p.startsWith("/actuator/") || p.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (setup.needsSetup()) {
            res.sendRedirect(req.getContextPath() + "/setup");
            return;
        }
        chain.doFilter(req, res);
    }
}
