package com.hardwarebilling.web;

import com.hardwarebilling.service.InvoiceRequests.InvoiceRequest;
import com.hardwarebilling.service.InvoiceRequests.LineRequest;
import com.hardwarebilling.service.InvoiceService;
import com.hardwarebilling.service.ItemService;
import com.hardwarebilling.service.ItemService.ItemDto;
import com.hardwarebilling.service.ItemService.ItemForm;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** JSON endpoints used by the invoice grid (CSRF-protected like every other POST). */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final ItemService items;
    private final InvoiceService invoices;

    public ApiController(ItemService items, InvoiceService invoices) {
        this.items = items;
        this.invoices = invoices;
    }

    /** Autocomplete: exact short code or partial name. */
    @GetMapping("/items/search")
    public List<ItemDto> search(@RequestParam(defaultValue = "") String q) {
        return items.search(q, 12);
    }

    /** Possible duplicates shown before a new item is created. */
    @GetMapping("/items/similar")
    public List<ItemDto> similar(@RequestParam(defaultValue = "") String name) {
        return items.similar(name);
    }

    /** Inline "Create new item" from the invoice grid. */
    @PostMapping("/items")
    public ResponseEntity<?> createItem(@Valid @RequestBody ItemForm form, BindingResult br) {
        if (br.hasErrors()) {
            String msg = br.getFieldErrors().stream().map(e -> e.getField() + " " + e.getDefaultMessage()).findFirst().orElse("Invalid item");
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
        form.setId(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(ItemService.toDto(items.create(form)));
    }

    /** Explicit "update catalog price" action; invoice edits never change the catalog by themselves. */
    @PostMapping("/items/{id}/price")
    public ItemDto updatePrice(@PathVariable Long id, @RequestBody Map<String, BigDecimal> body) {
        return ItemService.toDto(items.updateCatalogPrice(id, body.get("price")));
    }

    /** Totals preview computed by the same server code that save/issue use. */
    @PostMapping("/invoices/calc")
    public Map<String, Object> calc(@RequestBody List<LineRequest> lines) {
        var t = invoices.preview(lines);
        return Map.of("lines", t.lines(), "gross", t.gross(), "discount", t.discount(), "net", t.net(), "tax", t.tax(), "grand", t.grand());
    }

    @PostMapping("/invoices")
    public Map<String, Object> saveDraft(@RequestBody InvoiceRequest req) {
        var inv = invoices.saveDraft(req);
        return Map.of("id", inv.getId(), "redirect", "/invoices/" + inv.getId());
    }
}
