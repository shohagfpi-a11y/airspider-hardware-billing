package com.hardwarebilling.web;

import com.hardwarebilling.service.BusinessException;
import com.hardwarebilling.service.CompanyService.CompanyForm;
import com.hardwarebilling.service.SetupService;
import com.hardwarebilling.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AuthController {

    @Data
    public static class SetupForm {
        @NotBlank private String token;
        @NotBlank @Size(min = 3, max = 60) @Pattern(regexp = "[A-Za-z0-9._-]+") private String adminUsername;
        @NotBlank @Size(max = 120) private String adminName;
        private String password;
        private String confirm;
        @Valid private CompanyForm company = new CompanyForm();
    }

    private static final Object SETUP_LOCK = new Object();
    private final SetupService setup;
    private final UserService users;
    private final com.hardwarebilling.service.CompanyService company;
    private final com.hardwarebilling.service.FileStorageService files;

    public AuthController(SetupService setup, UserService users, com.hardwarebilling.service.CompanyService company,
                          com.hardwarebilling.service.FileStorageService files) {
        this.setup = setup;
        this.users = users;
        this.company = company;
        this.files = files;
    }

    /** The company logo only (never stamp or signature) is public so the sign-in page can show it. */
    @GetMapping("/brand/logo")
    public org.springframework.http.ResponseEntity<byte[]> brandLogo() {
        if (!company.exists()) return org.springframework.http.ResponseEntity.notFound().build();
        String name = company.get().getLogoFile();
        byte[] bytes = files.read(name);
        if (bytes == null) return org.springframework.http.ResponseEntity.notFound().build();
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofMinutes(10)))
                .contentType(org.springframework.http.MediaType.parseMediaType(files.contentType(name)))
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    @GetMapping("/login")
    public String login(Model model) {
        if (company.exists()) {
            var c = company.get();
            model.addAttribute("co", c);
            model.addAttribute("hasLogo", files.exists(c.getLogoFile()));
        }
        return "login";
    }

    @GetMapping("/setup")
    public String setupPage(Model model) {
        if (!setup.needsSetup()) return "redirect:/login";
        model.addAttribute("form", new SetupForm());
        return "setup";
    }

    @PostMapping("/setup")
    public String setupSubmit(@Valid @ModelAttribute("form") SetupForm form, BindingResult br, Model model, RedirectAttributes ra) {
        if (!setup.needsSetup()) return "redirect:/login";
        if (!br.hasErrors()) {
            if (form.getPassword() == null || !form.getPassword().equals(form.getConfirm())) {
                br.rejectValue("confirm", "mismatch", "Passwords do not match");
            }
        }
        if (br.hasErrors()) return "setup";
        try {
            // lock around the transactional call so a second request only starts after the first one has committed
            synchronized (SETUP_LOCK) {
                setup.complete(form.getToken(), form.getCompany(), form.getAdminUsername(), form.getAdminName(), form.getPassword());
            }
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            return "setup";
        }
        ra.addFlashAttribute("success", "Setup complete. Sign in with the administrator account you just created.");
        return "redirect:/login";
    }

    @GetMapping("/account/password")
    public String passwordPage() {
        return "account-password";
    }

    @PostMapping("/account/password")
    public String changePassword(@RequestParam String current, @RequestParam String next, @RequestParam String confirm, RedirectAttributes ra) {
        if (!next.equals(confirm)) throw new BusinessException("New passwords do not match");
        users.changeOwnPassword(current, next);
        ra.addFlashAttribute("success", "Password changed");
        return "redirect:/";
    }
}
