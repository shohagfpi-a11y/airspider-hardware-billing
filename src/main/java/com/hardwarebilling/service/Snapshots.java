package com.hardwarebilling.service;

import com.hardwarebilling.domain.BankAccount;
import com.hardwarebilling.domain.CompanyProfile;
import com.hardwarebilling.domain.Customer;

/** Immutable copies of master data stored (as JSON) on an invoice when it is issued. */
public final class Snapshots {
    private Snapshots() {}

    public record CompanySnap(String name, String legalName, String address, String city, String country, String phone,
                              String mobile, String email, String website, String taxNumber, String currency,
                              String taxLabel, String logoFile, String stampFile, String signatureFile,
                              String declaration, String footerNote) {
        public static CompanySnap of(CompanyProfile c) {
            return new CompanySnap(c.getDisplayName(), c.getLegalName(), c.getAddress(), c.getCity(), c.getCountry(),
                    c.getPhone(), c.getMobile(), c.getEmail(), c.getWebsite(), c.getTaxNumber(), c.getCurrency(),
                    c.getTaxLabel(), c.getLogoFile(), c.getStampFile(), c.getSignatureFile(), c.getDeclarationText(), c.getInvoiceFooterNote());
        }
    }

    public record CustomerSnap(String code, String name, String contactPerson, String mobile, String phone,
                               String email, String address, String taxNumber) {
        public static CustomerSnap of(Customer c) {
            return new CustomerSnap(c.getCode(), c.getName(), c.getContactPerson(), c.getMobile(), c.getPhone(),
                    c.getEmail(), c.getBillingAddress(), c.getTaxNumber());
        }
    }

    public record BankSnap(String bankName, String accountHolder, String accountNumber, String iban, String swift,
                           String branch, String currency) {
        public static BankSnap of(BankAccount b) {
            return b == null ? null : new BankSnap(b.getBankName(), b.getAccountHolder(), b.getAccountNumber(),
                    b.getIban(), b.getSwift(), b.getBranch(), b.getCurrency());
        }
    }
}
