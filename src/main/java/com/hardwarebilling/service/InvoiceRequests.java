package com.hardwarebilling.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** JSON payloads of the invoice grid. The server never trusts any totals sent by the browser. */
public final class InvoiceRequests {
    private InvoiceRequests() {}

    public record LineRequest(Long itemId, String itemCode, String serialNo, String description, BigDecimal quantity,
                              String unit, BigDecimal unitPrice, BigDecimal discountPercent, BigDecimal taxRate,
                              Boolean warrantyEnabled, Integer warrantyDuration, String warrantyUnit,
                              String warrantyTerms) {}

    public record InvoiceRequest(Long id, Long version, Long customerId, LocalDate invoiceDate, LocalDate dueDate,
                                 String deliveryNoteNo, String lpoNo, LocalDate deliveryDate, Long bankAccountId,
                                 String paymentTerms, String notes, List<LineRequest> lines) {}
}
