-- Billing now uses the plain selling price (no GST/tax added). Clear any tax rates already saved.
UPDATE products SET tax_rate = 0;
