package com.hardwarebilling.domain;

/** Small enums grouped in one file for readability. */
public final class Enums {
    private Enums() {}

    public enum Role { ADMIN, STAFF, READ_ONLY }
    public enum ItemType { PRODUCT, SERVICE }
    public enum WarrantyUnit { DAYS, MONTHS, YEARS }
    public enum DocStatus { DRAFT, ISSUED, VOIDED }
    public enum PaymentStatus { UNPAID, PARTIALLY_PAID, PAID }
    public enum RecordStatus { ACTIVE, REVERSED }
    public enum PaymentMethod {
        CASH("Cash"), BANK_TRANSFER("Bank transfer"), CARD("Card"), CHEQUE("Cheque"), OTHER("Other");
        private final String label;
        PaymentMethod(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
}
