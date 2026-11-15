-- Hardware Billing: initial schema. One installation = one company.
-- Money columns are NUMERIC (never floating point). Posted financial rows are never hard-deleted.

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(60)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN','STAFF','READ_ONLY')),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE company_profile (
    id                     BIGINT PRIMARY KEY CHECK (id = 1),
    legal_name             VARCHAR(200) NOT NULL,
    trading_name           VARCHAR(200),
    address                VARCHAR(400),
    city                   VARCHAR(100),
    country                VARCHAR(100),
    phone                  VARCHAR(50),
    mobile                 VARCHAR(50),
    email                  VARCHAR(150),
    website                VARCHAR(150),
    tax_number             VARCHAR(50),
    currency               VARCHAR(3)      NOT NULL DEFAULT 'AED',
    timezone               VARCHAR(60)  NOT NULL DEFAULT 'Asia/Dubai',
    invoice_prefix         VARCHAR(20)  NOT NULL DEFAULT 'INV-',
    receipt_prefix         VARCHAR(20)  NOT NULL DEFAULT 'RC-',
    default_payment_terms_days INT      NOT NULL DEFAULT 30,
    default_payment_terms  VARCHAR(500),
    default_notes          VARCHAR(1000),
    declaration_text       VARCHAR(1000),
    tax_label              VARCHAR(30)  NOT NULL DEFAULT 'VAT',
    default_tax_rate       NUMERIC(7,4) NOT NULL DEFAULT 5,
    logo_file              VARCHAR(100),
    stamp_file             VARCHAR(100),
    signature_file         VARCHAR(100),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Generic gap-tolerant, concurrency-safe counters. Rows are locked with SELECT ... FOR UPDATE.
CREATE TABLE sequence_counter (
    name       VARCHAR(40) PRIMARY KEY,
    next_value BIGINT NOT NULL
);
INSERT INTO sequence_counter(name, next_value) VALUES
  ('INVOICE', 1), ('RECEIPT', 1), ('ITEM', 1), ('CUSTOMER', 1);

CREATE TABLE bank_account (
    id             BIGSERIAL PRIMARY KEY,
    bank_name      VARCHAR(120) NOT NULL,
    account_holder VARCHAR(160) NOT NULL,
    account_number VARCHAR(60),
    iban           VARCHAR(50),
    swift          VARCHAR(20),
    branch         VARCHAR(120),
    currency       VARCHAR(3)      NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_bank_default ON bank_account (is_default) WHERE is_default;

CREATE TABLE customer (
    id                    BIGSERIAL PRIMARY KEY,
    code                  VARCHAR(20)  NOT NULL UNIQUE,
    name                  VARCHAR(200) NOT NULL,
    contact_person        VARCHAR(120),
    mobile                VARCHAR(50),
    phone                 VARCHAR(50),
    email                 VARCHAR(150),
    billing_address       VARCHAR(400),
    tax_number            VARCHAR(50),
    default_payment_terms_days INT,
    notes                 VARCHAR(1000),
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_customer_name ON customer (lower(name));

CREATE TABLE item (
    id               BIGSERIAL PRIMARY KEY,
    code             VARCHAR(20)  NOT NULL UNIQUE,
    item_type        VARCHAR(10)  NOT NULL CHECK (item_type IN ('PRODUCT','SERVICE')),
    name             VARCHAR(250) NOT NULL,
    description      VARCHAR(1000),
    unit             VARCHAR(20)  NOT NULL DEFAULT 'pcs',
    default_price    NUMERIC(19,6) NOT NULL CHECK (default_price >= 0),
    tax_rate         NUMERIC(7,4) NOT NULL DEFAULT 0 CHECK (tax_rate >= 0),
    warranty_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    warranty_duration INT,
    warranty_unit    VARCHAR(10)  CHECK (warranty_unit IN ('DAYS','MONTHS','YEARS')),
    warranty_terms   VARCHAR(1000),
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_item_name ON item (lower(name));

CREATE TABLE invoice (
    id                BIGSERIAL PRIMARY KEY,
    version           BIGINT       NOT NULL DEFAULT 0,
    invoice_number    VARCHAR(40)  UNIQUE,            -- assigned at issue time, never reused
    doc_status        VARCHAR(10)  NOT NULL CHECK (doc_status IN ('DRAFT','ISSUED','VOIDED')),
    customer_id       BIGINT       NOT NULL REFERENCES customer(id),
    invoice_date      DATE         NOT NULL,
    due_date          DATE         NOT NULL,
    delivery_note_no  VARCHAR(120),
    lpo_no            VARCHAR(120),
    delivery_date     DATE,
    bank_account_id   BIGINT       REFERENCES bank_account(id),
    currency          VARCHAR(3)      NOT NULL,
    payment_terms     VARCHAR(500),
    notes             VARCHAR(1000),
    gross_total       NUMERIC(19,4) NOT NULL DEFAULT 0,
    discount_total    NUMERIC(19,4) NOT NULL DEFAULT 0,
    net_total         NUMERIC(19,4) NOT NULL DEFAULT 0,
    tax_total         NUMERIC(19,4) NOT NULL DEFAULT 0,
    grand_total       NUMERIC(19,4) NOT NULL DEFAULT 0,
    -- immutable snapshots captured when the invoice is issued (JSON text)
    company_snapshot  TEXT,
    customer_snapshot TEXT,
    bank_snapshot     TEXT,
    issued_at         TIMESTAMPTZ,
    issued_by         VARCHAR(60),
    voided_at         TIMESTAMPTZ,
    voided_by         VARCHAR(60),
    void_reason       VARCHAR(500),
    created_by        VARCHAR(60),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_invoice_customer ON invoice (customer_id);
CREATE INDEX ix_invoice_date ON invoice (invoice_date);

CREATE TABLE invoice_line (
    id               BIGSERIAL PRIMARY KEY,
    invoice_id       BIGINT       NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,
    line_no          INT          NOT NULL,
    item_id          BIGINT       REFERENCES item(id),
    item_code        VARCHAR(20),
    serial_no        VARCHAR(120),
    description      VARCHAR(500) NOT NULL,
    quantity         NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
    unit             VARCHAR(20),
    unit_price       NUMERIC(19,6) NOT NULL CHECK (unit_price >= 0),
    discount_percent NUMERIC(7,4) NOT NULL DEFAULT 0 CHECK (discount_percent >= 0 AND discount_percent <= 100),
    tax_rate         NUMERIC(7,4) NOT NULL DEFAULT 0 CHECK (tax_rate >= 0),
    gross_amount     NUMERIC(19,4) NOT NULL,
    discount_amount  NUMERIC(19,4) NOT NULL,
    net_amount       NUMERIC(19,4) NOT NULL,
    tax_amount       NUMERIC(19,4) NOT NULL,
    line_total       NUMERIC(19,4) NOT NULL,
    warranty_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    warranty_duration INT,
    warranty_unit    VARCHAR(10),
    warranty_terms   VARCHAR(1000),
    warranty_start   DATE,
    warranty_end     DATE
);
CREATE INDEX ix_line_invoice ON invoice_line (invoice_id);

CREATE TABLE payment (
    id              BIGSERIAL PRIMARY KEY,
    receipt_no      VARCHAR(40)  NOT NULL UNIQUE,
    customer_id     BIGINT       NOT NULL REFERENCES customer(id),
    payment_date    DATE         NOT NULL,
    amount          NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    method          VARCHAR(20)  NOT NULL CHECK (method IN ('CASH','BANK_TRANSFER','CARD','CHEQUE','OTHER')),
    bank_account_id BIGINT       REFERENCES bank_account(id),
    bank_label      VARCHAR(250),
    reference       VARCHAR(120),
    notes           VARCHAR(1000),
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVERSED')),
    idempotency_key VARCHAR(64)  UNIQUE,               -- prevents duplicate form submission
    reversed_at     TIMESTAMPTZ,
    reversed_by     VARCHAR(60),
    reverse_reason  VARCHAR(500),
    created_by      VARCHAR(60),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_payment_customer ON payment (customer_id);

CREATE TABLE payment_allocation (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      BIGINT       NOT NULL REFERENCES payment(id),
    invoice_id      BIGINT       NOT NULL REFERENCES invoice(id),
    amount          NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    allocation_date DATE         NOT NULL,
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVERSED')),
    reversed_at     TIMESTAMPTZ,
    reversed_by     VARCHAR(60),
    reverse_reason  VARCHAR(500),
    created_by      VARCHAR(60),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_alloc_payment ON payment_allocation (payment_id);
CREATE INDEX ix_alloc_invoice ON payment_allocation (invoice_id);

CREATE TABLE refund (
    id           BIGSERIAL PRIMARY KEY,
    payment_id   BIGINT       NOT NULL REFERENCES payment(id),
    customer_id  BIGINT       NOT NULL REFERENCES customer(id),
    amount       NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    refund_date  DATE         NOT NULL,
    method       VARCHAR(20)  NOT NULL,
    reference    VARCHAR(120),
    notes        VARCHAR(500),
    created_by   VARCHAR(60),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_refund_payment ON refund (payment_id);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    username    VARCHAR(60),
    action      VARCHAR(60)  NOT NULL,
    entity_type VARCHAR(40),
    entity_id   VARCHAR(40),
    details     VARCHAR(2000)
);
CREATE INDEX ix_audit_at ON audit_log (at DESC);
