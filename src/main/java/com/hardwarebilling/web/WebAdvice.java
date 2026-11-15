package com.hardwarebilling.web;

import com.hardwarebilling.service.BusinessException;
import com.hardwarebilling.service.CompanyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import java.util.Map;

@ControllerAdvice
public class WebAdvice {
    private final CompanyService company;
    private final String appVersion;

    public WebAdvice(CompanyService company, org.springframework.beans.factory.ObjectProvider<org.springframework.boot.info.BuildProperties> build) {
        this.company = company;
        var b = build.getIfAvailable();
        this.appVersion = b == null ? "dev" : b.getVersion();
    }

    /** Application version from pom.xml (via build-info), shown in the menu and on the sign-in page. */
    @ModelAttribute("appVersion")
    public String appVersion() {
        return appVersion;
    }

    public record Me(String username, boolean admin, boolean canWrite) {}

    @ModelAttribute("me")
    public Me me() {
        var a = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return new Me("", false, false);
        boolean admin = a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_ADMIN"));
        boolean staff = a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals("ROLE_STAFF"));
        return new Me(a.getName(), admin, admin || staff);
    }

    /** companyName + brandLogo for the layout, from a single company lookup per request. */
    @ModelAttribute
    public void brand(Model model) {
        String name = "Hardware Billing";
        boolean logo = false;
        try {
            if (company.exists()) {
                var c = company.get();
                name = c.getDisplayName();
                logo = c.getLogoFile() != null;
            }
        } catch (Exception ignored) {
            // before setup
        }
        model.addAttribute("companyName", name);
        model.addAttribute("brandLogo", logo);
    }

    @ExceptionHandler(BusinessException.class)
    public Object business(BusinessException e, HttpServletRequest req, Model model) {
        return respond(e.getMessage(), req, model, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public Object conflict(ObjectOptimisticLockingFailureException e, HttpServletRequest req, Model model) {
        return respond("Someone else changed this record at the same time. Please reload and try again.", req, model, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public Object integrity(DataIntegrityViolationException e, HttpServletRequest req, Model model) {
        return respond("The data could not be saved (it conflicts with existing records).", req, model, HttpStatus.CONFLICT);
    }

    /** The Referer as a local path, or null when it points to another site (prevents an open redirect on "Go back"). */
    static String sameSitePath(HttpServletRequest req) {
        String ref = req.getHeader("Referer");
        if (ref == null) return null;
        try {
            java.net.URI u = java.net.URI.create(ref);
            if (u.getHost() == null || !u.getHost().equalsIgnoreCase(req.getServerName())) return null;
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            if (!path.startsWith("/") || path.startsWith("//")) return null;
            return u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Object respond(String message, HttpServletRequest req, Model model, HttpStatus status) {
        if (req.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.status(status).body(Map.of("error", message));
        }
        model.addAttribute("message", message);
        model.addAttribute("back", sameSitePath(req));
        return "error-business";
    }
}
