-- Phase 1: Sales Analytics schema enhancements & performance indexes
-- Adds transaction status and return tracking columns (defaulting to COMPLETED and 0 so existing flows remain untouched)
ALTER TABLE sales ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE sales ADD COLUMN IF NOT EXISTS refunded_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0);
ALTER TABLE sale_items ADD COLUMN IF NOT EXISTS returned_qty NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (returned_qty >= 0);

-- Composite indexes for fast analytical aggregations over sales, sale_items, payments, and products
CREATE INDEX IF NOT EXISTS idx_sales_status_created ON sales(status, created_at);
CREATE INDEX IF NOT EXISTS idx_sales_cashier_created ON sales(cashier_id, created_at);
CREATE INDEX IF NOT EXISTS idx_sale_items_sale_product ON sale_items(sale_id, product_id);
CREATE INDEX IF NOT EXISTS idx_sale_payments_sale_method ON sale_payments(sale_id, method);
CREATE INDEX IF NOT EXISTS idx_products_active_category ON products(is_active, category_id);
