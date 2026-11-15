-- Optional product barcode (EAN/UPC/any scanner value) so a scanned barcode finds the item on the invoice grid.
ALTER TABLE item ADD COLUMN barcode VARCHAR(64);
CREATE UNIQUE INDEX uq_item_barcode ON item (barcode) WHERE barcode IS NOT NULL;
