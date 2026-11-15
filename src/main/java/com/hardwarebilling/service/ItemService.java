package com.hardwarebilling.service;

import com.hardwarebilling.domain.Enums.ItemType;
import com.hardwarebilling.domain.Enums.WarrantyUnit;
import com.hardwarebilling.domain.Item;
import com.hardwarebilling.repo.ItemRepository;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

@Service
public class ItemService {

    @Data
    public static class ItemForm {
        private Long id;
        private ItemType itemType = ItemType.PRODUCT;
        @NotBlank @Size(max = 250) private String name;
        @Size(max = 64) private String barcode;
        @Size(max = 120) private String serialNo;
        @Size(max = 1000) private String description;
        @NotBlank @Size(max = 20) private String unit = "pcs";
        @NotNull @DecimalMin("0") private BigDecimal defaultPrice = BigDecimal.ZERO;
        @NotNull @DecimalMin("0") private BigDecimal taxRate = BigDecimal.ZERO;
        private boolean warrantyEnabled;
        private Integer warrantyDuration;
        private WarrantyUnit warrantyUnit = WarrantyUnit.MONTHS;
        @Size(max = 1000) private String warrantyTerms;
        private boolean active = true;
    }

    /** Shape returned to the invoice grid autocomplete. */
    public record ItemDto(Long id, String code, String barcode, String serialNo, String name, String description, String unit, BigDecimal price,
                          BigDecimal taxRate, boolean warrantyEnabled, Integer warrantyDuration,
                          String warrantyUnit, String warrantyTerms, String type, boolean active) {}

    private final ItemRepository repo;
    private final NumberingService numbering;
    private final AuditService audit;

    public ItemService(ItemRepository repo, NumberingService numbering, AuditService audit) {
        this.repo = repo;
        this.numbering = numbering;
        this.audit = audit;
    }

    public static ItemDto toDto(Item i) {
        return new ItemDto(i.getId(), i.getCode(), i.getBarcode(), i.getSerialNo(), i.getName(), i.getDescription(), i.getUnit(), i.getDefaultPrice(),
                i.getTaxRate(), i.isWarrantyEnabled(), i.getWarrantyDuration(),
                i.getWarrantyUnit() == null ? null : i.getWarrantyUnit().name(), i.getWarrantyTerms(),
                i.getItemType().name(), i.isActive());
    }

    private static String like(String q) {
        return q.trim().replace("\\", "").replace("%", "").replace("_", "");
    }

    @Transactional(readOnly = true)
    public List<ItemDto> search(String q, int limit) {
        String s = like(q == null ? "" : q);
        if (s.isEmpty()) return List.of();
        // typing just a number finds that product number (5 -> P00005); a scanned barcode matches the barcode column
        String alt = s.matches("[0-9]{1,5}") ? "P" + String.format("%05d", Integer.parseInt(s)) : s;
        return repo.searchActive(s, alt, PageRequest.of(0, limit)).stream().map(ItemService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<Item> list(String q) {
        return repo.searchAll(like(q == null ? "" : q), PageRequest.of(0, 300));
    }

    @Transactional(readOnly = true)
    public Item get(Long id) {
        return repo.findById(id).orElseThrow(() -> new BusinessException("Item not found"));
    }

    /** Possible duplicates by name (case-insensitive, partial). Informational only: variants are allowed. */
    @Transactional(readOnly = true)
    public List<ItemDto> similar(String name) {
        String s = like(name == null ? "" : name);
        if (s.length() < 2) return List.of();
        return repo.searchAll(s, PageRequest.of(0, 5)).stream().filter(i -> i.getName().toLowerCase().contains(s.toLowerCase()))
                .map(ItemService::toDto).toList();
    }

    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public Item create(ItemForm f) {
        Item i = new Item();
        // code from a forward-only counter: unique, concurrency-safe, never reused even after deactivation
        i.setCode("P" + String.format("%05d", numbering.next("ITEM")));
        apply(i, f);
        repo.save(i);
        audit.log("ITEM_CREATE", "Item", i.getId(), i.getCode() + " " + i.getName());
        return i;
    }

    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public Item update(ItemForm f) {
        Item i = get(f.getId());
        apply(i, f);
        i.setActive(f.isActive());
        audit.log("ITEM_UPDATE", "Item", i.getId(), i.getCode() + " " + i.getName());
        return i;
    }

    /** Explicit, separate action: invoice edits never touch the catalog silently. */
    @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    @Transactional
    public Item updateCatalogPrice(Long id, BigDecimal newPrice) {
        if (newPrice == null || newPrice.signum() < 0) throw new BusinessException("Invalid price");
        Item i = get(id);
        BigDecimal old = i.getDefaultPrice();
        i.setDefaultPrice(newPrice);
        audit.log("ITEM_PRICE_UPDATE", "Item", i.getId(), i.getCode() + " price " + old.toPlainString() + " -> " + newPrice.toPlainString());
        return i;
    }

    private void apply(Item i, ItemForm f) {
        i.setItemType(f.getItemType() == null ? ItemType.PRODUCT : f.getItemType());
        i.setName(f.getName().trim());
        String bc = blankToNull(f.getBarcode());
        if (bc != null) {
            var other = repo.findByBarcode(bc);
            if (other.isPresent() && !other.get().getId().equals(i.getId())) throw new BusinessException("Barcode " + bc + " is already used by " + other.get().getCode());
        }
        i.setBarcode(bc);
        i.setSerialNo(blankToNull(f.getSerialNo()));
        i.setDescription(blankToNull(f.getDescription()));
        i.setUnit(f.getUnit().trim());
        i.setDefaultPrice(f.getDefaultPrice());
        i.setTaxRate(f.getTaxRate());
        i.setWarrantyEnabled(f.isWarrantyEnabled());
        if (f.isWarrantyEnabled()) {
            if (f.getWarrantyDuration() == null || f.getWarrantyDuration() <= 0 || f.getWarrantyUnit() == null) {
                throw new BusinessException("Warranty duration and unit are required when warranty is enabled");
            }
            i.setWarrantyDuration(f.getWarrantyDuration());
            i.setWarrantyUnit(f.getWarrantyUnit());
            i.setWarrantyTerms(blankToNull(f.getWarrantyTerms()));
        } else {
            i.setWarrantyDuration(null);
            i.setWarrantyUnit(null);
            i.setWarrantyTerms(null);
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
