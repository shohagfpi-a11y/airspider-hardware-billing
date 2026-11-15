package com.hardwarebilling.web;

import com.hardwarebilling.domain.Enums.ItemType;
import com.hardwarebilling.domain.Enums.WarrantyUnit;
import com.hardwarebilling.domain.Item;
import com.hardwarebilling.service.BusinessException;
import com.hardwarebilling.service.CompanyService;
import com.hardwarebilling.service.ItemService;
import com.hardwarebilling.service.ItemService.ItemForm;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/items")
public class CatalogController {
    private final ItemService items;
    private final CompanyService company;

    public CatalogController(ItemService items, CompanyService company) {
        this.items = items;
        this.company = company;
    }

    @GetMapping
    public String list(@RequestParam(required = false, defaultValue = "") String q, Model model) {
        model.addAttribute("items", items.list(q));
        model.addAttribute("q", q);
        model.addAttribute("nav", "items");
        return "items";
    }

    @GetMapping("/new")
    public String create(Model model) {
        ItemForm f = new ItemForm();
        f.setTaxRate(company.get().getDefaultTaxRate());
        return form(f, null, model);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        Item i = items.get(id);
        ItemForm f = new ItemForm();
        f.setId(i.getId()); f.setItemType(i.getItemType()); f.setName(i.getName()); f.setBarcode(i.getBarcode()); f.setSerialNo(i.getSerialNo()); f.setDescription(i.getDescription());
        f.setUnit(i.getUnit()); f.setDefaultPrice(i.getDefaultPrice()); f.setTaxRate(i.getTaxRate());
        f.setWarrantyEnabled(i.isWarrantyEnabled()); f.setWarrantyDuration(i.getWarrantyDuration());
        f.setWarrantyUnit(i.getWarrantyUnit() == null ? WarrantyUnit.MONTHS : i.getWarrantyUnit());
        f.setWarrantyTerms(i.getWarrantyTerms()); f.setActive(i.isActive());
        return form(f, i.getCode(), model);
    }

    private String form(ItemForm f, String code, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("code", code);
        model.addAttribute("types", ItemType.values());
        model.addAttribute("units", WarrantyUnit.values());
        model.addAttribute("nav", "items");
        return "item-form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("form") ItemForm f, BindingResult br, Model model, RedirectAttributes ra) {
        String code = f.getId() == null ? null : items.get(f.getId()).getCode();
        if (br.hasErrors()) return form(f, code, model);
        try {
            Item saved = f.getId() == null ? items.create(f) : items.update(f);
            ra.addFlashAttribute("success", "Item " + saved.getCode() + " saved");
            return "redirect:/items";
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            return form(f, code, model);
        }
    }
}
