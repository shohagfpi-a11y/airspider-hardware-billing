-- Short notice printed at the bottom of every invoice (editable in Company settings).
ALTER TABLE company_profile ADD COLUMN invoice_footer_note VARCHAR(500)
  DEFAULT 'Please check this invoice on receipt. If you find any issue, inform us within 3 days; after that the invoice is considered accepted and cannot be changed.';
UPDATE company_profile SET invoice_footer_note =
  'Please check this invoice on receipt. If you find any issue, inform us within 3 days; after that the invoice is considered accepted and cannot be changed.'
  WHERE invoice_footer_note IS NULL;
