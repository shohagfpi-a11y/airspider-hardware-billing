# Hardware Billing

Invoicing, payments, customer advances and outstanding balances for a hardware business.
One installation serves **one company** (several staff accounts); deploy a separate installation (own database,
uploads, config and domain) per customer.

Stack: Java 21, Spring Boot 4.0 (MVC, Security, Data JPA, Validation), PostgreSQL 16, Flyway, Thymeleaf + Bootstrap 5,
OpenHTMLtoPDF (PDF, LGPL-3.0, used unmodified as a library), ZXing (Code 128 barcode, Apache-2.0), Maven, Docker Compose + Nginx.

---

## 1. Try it on your own PC (Windows / Linux / macOS)

You only need **Java 21**. Nothing else: Maven is downloaded by the wrapper, and a real PostgreSQL server is started
automatically from bundled binaries (data kept in `./data/pg`).

**With sample data (fastest way to look around):**

```bash
scripts\run-local-demo.cmd          # Windows
./scripts/run-local.sh              # Linux/macOS: SEED_DEMO=true ./scripts/run-local.sh
```

Open <http://localhost:8080> and sign in. **Demo logins (development only, never used in production):**

| Role | Username | Password |
|---|---|---|
| Administrator | `admin` | `Demo#Admin1234` |
| Staff | `staff` | `Demo#Staff1234` |
| Read-only | `viewer` | `Demo#View12345` |

**Double-click on Windows:** `START-APP.cmd` (start), `STOP-APP.cmd` (stop), `RESET-DEMO.cmd` (erase local test data and load the demo).

The demo recreates the reference invoice **INV-12946** (AL Shahab Al Abyad Sanitary & Electricity WR Tr. LLC to Munir R M Al Kaloti,
21 hardware lines, delivery notes 1523/1524, LPO pur-ord-2026-0429, 5% VAT, total AED 1,170.23) with a generated company logo,
a generated demo stamp and signature, and a part payment. These details exist only in the development seed, never in the application code.

The demo data includes a customer with the **AED 1,050 scenario already run up to step 4** (advance 300 allocated,
500 received): invoice balance is 200, so you can do step 5 yourself (receive 250, allocate 200, see AED 50 credit).

**Without sample data (real first-time setup flow):** run `scripts\run-local.cmd`. The console prints a one-time
**setup token**; open <http://localhost:8080/setup>, enter it, and create the company profile and administrator.

To start from scratch, stop the app and delete the `data` folder.

Run the tests (53 tests, uses its own throw-away PostgreSQL):

```bash
mvnw.cmd test        # Windows        ./mvnw test   # Linux/macOS
```

After the tests, `target/pdf-samples/` contains sample PDFs (short invoice, 90-line multi-page invoice, draft, receipt,
statement) plus PNG previews of their pages.

## 2. What is implemented

* **Company profile**: legal/trading name, address, contacts, TRN, currency (default AED), timezone (default Asia/Dubai),
  invoice/receipt prefixes, next invoice sequence (raise-only), payment terms, notes, declaration, default tax,
  logo, stamp and signature images (PNG/JPEG, 2 MB, magic-byte + decode validation, protected access).
* **Bank accounts**: many, active/inactive, one default; chosen per invoice and printed on it.
* **Customers**: code `C00001…`, contacts, TRN, default terms; page with invoices, receipts, allocations,
  **outstanding receivable and available credit shown separately**.
* **Products**: permanent unique codes `P00001…` (forward-only counter: concurrency-safe, never reused, even after
  deactivation), optional **barcode**, Product/Service, unit, price, tax; duplicate-name warning; variants allowed.
* **Invoice screen** (works on phones: each line becomes a card): the cursor always starts in the **product box**.
  Type a **number** (5 finds P00005), a **product code**, or **scan a barcode** and Enter: that product is loaded from the
  database; type a **word** and matching products are listed below. *Create new product* is offered inline (saved to the
  catalog and inserted without losing invoice data). Pressing Enter after a line moves the cursor to the next line's
  product box. A small **Warranty checkbox** on each line opens a popup (duration, period, terms); cancel = stays
  unticked. Warranty starts at the delivery date, else the invoice date. **Payment: Cash / Bank**: the bank list appears
  only for Bank and the default (or only) account is pre-selected. Totals are previewed by the **server**
  (same code as save/issue); draft → issued → voided, PDF and print. Explicit *Set as catalog price* action.
  later edits never change them. Issued invoices are not editable/deletable; voiding needs a reason and is blocked
  while payments are allocated. Invoice numbers are assigned at issue, gap-free, never reused.
* **Payments**: receipts, partial/multiple payments, one receipt over several invoices, advances, overpayment kept as
  credit, applying credit = allocation (not a new receipt), audited reversal of allocations/receipts, refunds of credit,
  duplicate-submission protection, row locking against double spending. Receipt PDF/print.
* **Statements**: chronological, no double counting (allocations are information rows), PDF/print/CSV.
* **Dashboard & reports**: invoiced value (invoice date), cash received (payment date), outstanding, overdue,
  available credit; invoice list, outstanding, receipts, customer credit (CSV export). No profit figures.
* **Left-side menu**: fixed sidebar on desktop, slide-in drawer (hamburger) on phones; invoice-number lookup box inside it.
* **Security**: BCrypt, CSRF, secure cookies, login lock-out (IP and username), server-side role checks
  (URL rules **and** method security), validation, protected uploads, audit log, no secrets in logs.
* **Barcode**: Code 128 of the invoice number with the number printed below. Scanning (or typing) it into the search
  box in the top bar opens the invoice **inside the authenticated portal**. There is no public barcode URL.

### Roles

| | Administrator | Staff | Read-only |
|---|:-:|:-:|:-:|
| View everything, reports, PDFs | ✔ | ✔ | ✔ |
| Customers, catalog, invoices (draft/issue), receipts, apply credit | ✔ | ✔ | |
| Void invoice, reverse allocation/receipt, refund | ✔ | | |
| Company settings, banks, users, audit log | ✔ | | |

### Rounding policy (UI, database, PDF, print, reports)

Money is `BigDecimal` / PostgreSQL `NUMERIC`, never floating point. Prices are **tax-exclusive**. For every line:
`gross = round(qty × price)`, `discount = round(gross × disc%)`, `net = gross − discount`, `tax = round(net × rate%)`,
`total = net + tax`, where `round` is **HALF_UP to the currency's decimals** (AED 2, BHD 3, JPY 0). Invoice totals are the
exact **sum of the rounded line amounts**, so printed lines always add up. Unit prices keep up to 6 decimals, quantities up to 4.
Payment amounts must not have more decimals than the currency.

## 3. Verification performed

* 53 automated tests: money rules (rounding, decimals, no float drift, sample-invoice totals), the **AED 1,050 scenario**,
  allocation limits, other-customer rejection, overpayment credit, reversals/void rules, refunds, duplicate receipts,
  **concurrency** (8 threads cannot overspend one receipt, cannot overpay one invoice, unique gap-free invoice numbers,
  unique item codes, parallel duplicate submission), historical snapshots after company/customer/bank/item edits, item
  creation → search by code/partial name → reuse, warranty dates and snapshot, role permissions (service and HTTP),
  CSRF, CSV formula injection, PDF generation (short + 90-line multi-page invoice, repeated header, page numbers, draft
  marking, warranty text, advance/“as of” block, receipt, statement) and PDF/print totals equal to server calculations.
* Fresh installation: every test context starts an empty PostgreSQL and runs all Flyway migrations.
* Manually in a browser: login, dashboard, invoice grid autocomplete, inline item creation, save draft, print view.

## 4. Limitations / not included (version one)

* No stock/inventory, warehouses, supplier purchasing/accounting, COGS/profit, general ledger, bank feeds, payment
  gateway, SaaS multi-tenancy, public invoice links/QR, buyer self-service login, government e-invoicing.
* Not a certified tax or e-invoicing system. The “TAX INVOICE” title is shown when a company TRN is configured;
  confirm the layout and wording with your tax advisor for your jurisdiction.
* PDFs embed a system font (DejaVu in the Docker image, Arial on Windows). Complex scripts (e.g. Arabic shaping,
  right-to-left layout) are not supported in PDFs; Latin text is.
* Refunds cannot be reversed in the UI (record a correcting receipt instead). One base currency per installation.
* Login lock-out state is in memory (single instance; resets on restart). Backups are manual/cron (see the guide).
* The `local` profile (embedded PostgreSQL, relaxed cookies) is for testing on your PC only.

## 5. Versions and releases

Current version: **0.0.4** (see [CHANGELOG.md](CHANGELOG.md)). The version lives only in `pom.xml` and is shown in the
app menu and on the sign-in page. To release a new version:

```bash
./mvnw versions:set -DnewVersion=0.0.2 -DgenerateBackupPoms=false   # 1. bump the version
# 2. add a "## [0.0.2]" section at the top of CHANGELOG.md
git commit -am "Release 0.0.2"                                       # 3. commit
git tag -a v0.0.2 -m "Version 0.0.2"                                 # 4. tag
git push && git push --tags                                          # 5. push
```

Patch (0.0.x) = fixes, minor (0.x.0) = new features, major (x.0.0) = breaking changes.

## 6. Production deployment

See **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**: Ubuntu VPS, Docker Compose, Nginx, Let's Encrypt HTTPS, custom domain DNS,
first administrator setup, migrations, backup/restore, upgrade and rollback.
