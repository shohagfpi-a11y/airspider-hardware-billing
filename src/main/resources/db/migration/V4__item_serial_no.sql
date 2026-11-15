-- Optional serial number kept on the product; copied onto the invoice line when the product is chosen.
ALTER TABLE item ADD COLUMN serial_no VARCHAR(120);
