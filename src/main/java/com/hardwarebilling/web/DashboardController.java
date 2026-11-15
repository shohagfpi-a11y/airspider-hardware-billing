package com.hardwarebilling.web;

import com.hardwarebilling.service.CompanyService;
import com.hardwarebilling.service.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.LocalDate;

@Controller
public class DashboardController {
    private final ReportService reports;
    private final CompanyService company;

    public DashboardController(ReportService reports, CompanyService company) {
        this.reports = reports;
        this.company = company;
    }

    @GetMapping("/")
    public String home(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       Model model) {
        LocalDate today = company.today();
        if (from == null) from = today.withDayOfMonth(1);
        if (to == null) to = today;
        if (to.isBefore(from)) to = from;
        model.addAttribute("d", reports.dashboard(from, to));
        model.addAttribute("nav", "dashboard");
        return "dashboard";
    }
}
