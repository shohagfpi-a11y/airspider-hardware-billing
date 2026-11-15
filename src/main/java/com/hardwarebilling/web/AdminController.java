package com.hardwarebilling.web;

import com.hardwarebilling.domain.AppUser;
import com.hardwarebilling.domain.BankAccount;
import com.hardwarebilling.domain.Enums.Role;
import com.hardwarebilling.service.*;
import com.hardwarebilling.service.BankService.BankForm;
import com.hardwarebilling.service.CompanyService.CompanyForm;
import com.hardwarebilling.service.UserService.UserForm;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Administrator screens: company settings, bank accounts, users, audit log; plus protected image access. */
@Controller
public class AdminController {
    private final CompanyService company;
    private final BankService banks;
    private final UserService users;
    private final AuditService audit;
    private final FileStorageService files;

    public AdminController(CompanyService company, BankService banks, UserService users, AuditService audit, FileStorageService files) {
        this.company = company;
        this.banks = banks;
        this.users = users;
        this.audit = audit;
        this.files = files;
    }

    // ---- company settings
    @GetMapping("/settings")
    public String settings(Model model) {
        return settingsView(company.toForm(company.get()), model);
    }

    private String settingsView(CompanyForm f, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("co", company.get());
        model.addAttribute("nav", "settings");
        return "settings";
    }

    @PostMapping("/settings")
    public String saveSettings(@Valid @ModelAttribute("form") CompanyForm f, BindingResult br, Model model,
                               @RequestParam(required = false) MultipartFile logo, @RequestParam(required = false) MultipartFile stamp,
                               @RequestParam(required = false) MultipartFile signature, RedirectAttributes ra) {
        if (br.hasErrors()) return settingsView(f, model);
        try {
            company.update(f, logo, stamp, signature);
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            return settingsView(f, model);
        }
        ra.addFlashAttribute("success", "Settings saved. Issued invoices keep their original details.");
        return "redirect:/settings";
    }

    // ---- bank accounts
    @GetMapping("/banks")
    public String bankList(Model model) {
        model.addAttribute("banks", banks.all());
        model.addAttribute("nav", "banks");
        return "banks";
    }

    @GetMapping("/banks/new")
    public String bankNew(Model model) {
        BankForm f = new BankForm();
        f.setCurrency(company.get().getCurrency());
        return bankForm(f, model);
    }

    @GetMapping("/banks/{id}/edit")
    public String bankEdit(@PathVariable Long id, Model model) {
        BankAccount b = banks.get(id);
        BankForm f = new BankForm();
        f.setId(b.getId()); f.setBankName(b.getBankName()); f.setAccountHolder(b.getAccountHolder());
        f.setAccountNumber(b.getAccountNumber()); f.setIban(b.getIban()); f.setSwift(b.getSwift()); f.setBranch(b.getBranch());
        f.setCurrency(b.getCurrency()); f.setActive(b.isActive()); f.setDefaultAccount(b.isDefaultAccount());
        return bankForm(f, model);
    }

    private String bankForm(BankForm f, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("nav", "banks");
        return "bank-form";
    }

    @PostMapping("/banks")
    public String bankSave(@Valid @ModelAttribute("form") BankForm f, BindingResult br, Model model, RedirectAttributes ra) {
        if (br.hasErrors()) return bankForm(f, model);
        banks.save(f);
        ra.addFlashAttribute("success", "Bank account saved");
        return "redirect:/banks";
    }

    // ---- users
    @GetMapping("/users")
    public String userList(Model model) {
        model.addAttribute("users", users.all());
        model.addAttribute("nav", "users");
        return "users";
    }

    @GetMapping("/users/new")
    public String userNew(Model model) {
        return userForm(new UserForm(), model);
    }

    @GetMapping("/users/{id}/edit")
    public String userEdit(@PathVariable Long id, Model model) {
        AppUser u = users.get(id);
        UserForm f = new UserForm();
        f.setId(u.getId()); f.setUsername(u.getUsername()); f.setFullName(u.getFullName()); f.setRole(u.getRole()); f.setActive(u.isActive());
        return userForm(f, model);
    }

    private String userForm(UserForm f, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("roles", Role.values());
        model.addAttribute("nav", "users");
        return "user-form";
    }

    @PostMapping("/users")
    public String userSave(@Valid @ModelAttribute("form") UserForm f, BindingResult br, Model model, RedirectAttributes ra) {
        if (br.hasErrors()) return userForm(f, model);
        try {
            users.save(f);
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            return userForm(f, model);
        }
        ra.addFlashAttribute("success", "User saved");
        return "redirect:/users";
    }

    // ---- audit log
    @GetMapping("/audit")
    public String auditLog(Model model) {
        model.addAttribute("rows", audit.recent(300));
        model.addAttribute("nav", "audit");
        return "audit";
    }

    // ---- protected images (logo/stamp/signature): authenticated users only, random file names
    @GetMapping("/files/{name:[a-f0-9\\-]{36}\\.(?:png|jpg)}")
    public ResponseEntity<byte[]> file(@PathVariable String name) {
        byte[] b = files.read(name);
        if (b == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(files.contentType(name))).header("X-Content-Type-Options", "nosniff").body(b);
    }
}
