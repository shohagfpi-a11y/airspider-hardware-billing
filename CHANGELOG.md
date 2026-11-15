# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/):
`MAJOR.MINOR.PATCH` (patch = fixes, minor = new features, major = breaking changes such as a data migration that needs manual steps).
The version is defined once, in `pom.xml`, and shown in the app menu and on the sign-in page.

## [0.0.2] - 2026-11-15

### Fixed
- Deployment behind Cloudflare (proxied / orange cloud): Nginx restores the real visitor IP from `CF-Connecting-IP`
  for Cloudflare address ranges only, so the login lock-out and rate limit work per visitor instead of per Cloudflare edge.
- Let's Encrypt renewal also answers the challenge on HTTPS, so it keeps working when Cloudflare "Always Use HTTPS" is on.

### Docs
- Cloudflare section in docs/DEPLOYMENT.md (DNS only while issuing the first certificate, then Proxied with SSL mode Full (strict)).

## [0.0.1] - 2026-11-15

First version.

### Added
- Company profile with logo, stamp and signature; multiple company bank accounts.
- Customers with outstanding receivable and available advance credit shown separately.
- Product and service catalogue: permanent codes (P00001...), optional barcode and serial number, default warranty.
- Invoice screen for desktop and phone: search by product number, code, barcode or name; inline "Create new product";
  per-line warranty checkbox with popup; Cash / Bank choice; totals calculated by the server.
- Draft / issued / voided invoices with frozen snapshots, gap-free invoice numbers, audited void.
- Payments: partial and multiple payments, advances, overpayment credit, allocation and receipt reversal, refunds,
  duplicate-submission protection and row locking.
- A4 documents (print view and PDF): tax invoice with Code 128 barcode, amount in words, 3-day issue notice,
  stamp and signature; payment receipt; customer statement; four reports (invoice list, outstanding, receipts,
  customer credit) with CSV export.
- Dashboard, left-side menu, sign-in page with company logo, roles (administrator, staff, read-only), login lock-out,
  CSRF protection, audit log.
- Windows double-click scripts (START-APP, STOP-APP, RESET-DEMO), Docker Compose + Nginx + Let's Encrypt deployment,
  backup / restore / upgrade scripts.

### Security
- Code review fixes: page-footer text can no longer inject HTML into print views; same-site-only "Go back" link;
  first-time setup cannot run twice concurrently; login failure counter forgets old isolated failures;
  client-supplied X-Forwarded-For is not trusted when there is no proxy.
