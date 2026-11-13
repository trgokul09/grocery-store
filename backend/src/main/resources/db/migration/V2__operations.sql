ALTER TABLE products ADD COLUMN tax_rate NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (tax_rate >= 0);

CREATE SEQUENCE po_seq START 1;
CREATE SEQUENCE invoice_seq START 1;

CREATE TABLE shifts (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at TIMESTAMPTZ,
  opening_cash NUMERIC(14,2) NOT NULL DEFAULT 0,
  counted_cash NUMERIC(14,2),
  expected_cash NUMERIC(14,2),
  variance NUMERIC(14,2),
  cash_sales NUMERIC(14,2) NOT NULL DEFAULT 0,
  cash_expenses NUMERIC(14,2) NOT NULL DEFAULT 0,
  supplier_cash_payments NUMERIC(14,2) NOT NULL DEFAULT 0,
  status VARCHAR(10) NOT NULL,
  notes VARCHAR(500)
);
CREATE UNIQUE INDEX uq_open_shift_per_user ON shifts(user_id) WHERE status = 'OPEN';

CREATE TABLE stock_movements (
  id BIGSERIAL PRIMARY KEY,
  product_id BIGINT NOT NULL REFERENCES products(id),
  movement_type VARCHAR(20) NOT NULL,
  quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
  previous_stock NUMERIC(14,3) NOT NULL,
  new_stock NUMERIC(14,3) NOT NULL CHECK (new_stock >= 0),
  ref_type VARCHAR(30),
  ref_id BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  user_id BIGINT REFERENCES users(id),
  reason VARCHAR(255)
);
CREATE INDEX idx_movements_product ON stock_movements(product_id, created_at DESC);

CREATE TABLE suppliers (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(150) NOT NULL,
  contact_person VARCHAR(100),
  phone VARCHAR(30),
  email VARCHAR(120),
  address VARCHAR(255),
  payment_terms_days INT NOT NULL DEFAULT 0,
  opening_balance NUMERIC(14,2) NOT NULL DEFAULT 0,
  approved BOOLEAN NOT NULL DEFAULT FALSE,
  approved_by BIGINT,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE supplier_products (
  id BIGSERIAL PRIMARY KEY,
  supplier_id BIGINT NOT NULL REFERENCES suppliers(id),
  product_id BIGINT NOT NULL REFERENCES products(id),
  supplier_price NUMERIC(12,2),
  lead_time_days INT NOT NULL DEFAULT 3,
  preferred BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (supplier_id, product_id)
);
CREATE TABLE supplier_ledger (
  id BIGSERIAL PRIMARY KEY,
  supplier_id BIGINT NOT NULL REFERENCES suppliers(id),
  entry_type VARCHAR(20) NOT NULL,
  amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
  ref_type VARCHAR(30),
  ref_id BIGINT,
  note VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by BIGINT REFERENCES users(id)
);
CREATE INDEX idx_ledger_supplier ON supplier_ledger(supplier_id, created_at);

CREATE TABLE purchase_orders (
  id BIGSERIAL PRIMARY KEY,
  po_number VARCHAR(30) NOT NULL UNIQUE,
  supplier_id BIGINT NOT NULL REFERENCES suppliers(id),
  status VARCHAR(20) NOT NULL,
  notes VARCHAR(500),
  expected_date DATE,
  created_by BIGINT REFERENCES users(id),
  approved_by BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  approved_at TIMESTAMPTZ
);
CREATE TABLE purchase_items (
  id BIGSERIAL PRIMARY KEY,
  purchase_order_id BIGINT NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
  product_id BIGINT NOT NULL REFERENCES products(id),
  ordered_qty NUMERIC(14,3) NOT NULL CHECK (ordered_qty > 0),
  unit_cost NUMERIC(12,2) NOT NULL CHECK (unit_cost >= 0),
  received_qty NUMERIC(14,3) NOT NULL DEFAULT 0,
  damaged_qty NUMERIC(14,3) NOT NULL DEFAULT 0
);
CREATE TABLE purchase_receipts (
  id BIGSERIAL PRIMARY KEY,
  purchase_order_id BIGINT NOT NULL REFERENCES purchase_orders(id),
  received_by BIGINT REFERENCES users(id),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  total_value NUMERIC(14,2) NOT NULL DEFAULT 0,
  paid_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
  payment_method VARCHAR(10),
  note VARCHAR(255)
);
CREATE TABLE purchase_receipt_items (
  id BIGSERIAL PRIMARY KEY,
  receipt_id BIGINT NOT NULL REFERENCES purchase_receipts(id) ON DELETE CASCADE,
  purchase_item_id BIGINT NOT NULL REFERENCES purchase_items(id),
  received_qty NUMERIC(14,3) NOT NULL,
  damaged_qty NUMERIC(14,3) NOT NULL DEFAULT 0,
  unit_cost NUMERIC(12,2) NOT NULL
);
CREATE TABLE supplier_payments (
  id BIGSERIAL PRIMARY KEY,
  supplier_id BIGINT NOT NULL REFERENCES suppliers(id),
  amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
  method VARCHAR(10) NOT NULL,
  receipt_id BIGINT REFERENCES purchase_receipts(id),
  shift_id BIGINT REFERENCES shifts(id),
  note VARCHAR(255),
  paid_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by BIGINT REFERENCES users(id)
);

CREATE TABLE sales (
  id BIGSERIAL PRIMARY KEY,
  invoice_no VARCHAR(30) NOT NULL UNIQUE,
  subtotal NUMERIC(14,2) NOT NULL DEFAULT 0,
  discount_total NUMERIC(14,2) NOT NULL DEFAULT 0,
  tax_total NUMERIC(14,2) NOT NULL DEFAULT 0,
  total NUMERIC(14,2) NOT NULL DEFAULT 0,
  cogs NUMERIC(14,2) NOT NULL DEFAULT 0,
  customer_name VARCHAR(100),
  cashier_id BIGINT NOT NULL REFERENCES users(id),
  shift_id BIGINT REFERENCES shifts(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sales_created ON sales(created_at);
CREATE TABLE sale_items (
  id BIGSERIAL PRIMARY KEY,
  sale_id BIGINT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
  product_id BIGINT NOT NULL REFERENCES products(id),
  product_name VARCHAR(150) NOT NULL,
  sku VARCHAR(60) NOT NULL,
  quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
  unit_price NUMERIC(12,2) NOT NULL,
  cost_price NUMERIC(12,2) NOT NULL,
  discount NUMERIC(12,2) NOT NULL DEFAULT 0,
  tax_rate NUMERIC(5,2) NOT NULL DEFAULT 0,
  tax_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
  line_total NUMERIC(14,2) NOT NULL
);
CREATE INDEX idx_sale_items_product ON sale_items(product_id);
CREATE TABLE sale_payments (
  id BIGSERIAL PRIMARY KEY,
  sale_id BIGINT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
  method VARCHAR(10) NOT NULL,
  amount NUMERIC(14,2) NOT NULL CHECK (amount > 0)
);

CREATE TABLE expenses (
  id BIGSERIAL PRIMARY KEY,
  category VARCHAR(20) NOT NULL,
  amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
  method VARCHAR(10) NOT NULL,
  description VARCHAR(255) NOT NULL,
  expense_date DATE NOT NULL,
  shift_id BIGINT REFERENCES shifts(id),
  created_by BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_expenses_date ON expenses(expense_date);

CREATE TABLE audit_logs (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT,
  username VARCHAR(50),
  action VARCHAR(50) NOT NULL,
  entity_type VARCHAR(50) NOT NULL,
  entity_id VARCHAR(50),
  old_value TEXT,
  new_value TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_created ON audit_logs(created_at DESC);
-- Immutability: audit rows can never be updated or deleted
CREATE FUNCTION audit_logs_immutable() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'audit_logs is immutable'; END; $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_audit_logs_immutable BEFORE UPDATE OR DELETE ON audit_logs
  FOR EACH ROW EXECUTE FUNCTION audit_logs_immutable();

CREATE TABLE notifications (
  id BIGSERIAL PRIMARY KEY,
  type VARCHAR(30) NOT NULL,
  message VARCHAR(300) NOT NULL,
  target_role VARCHAR(20) NOT NULL,
  ref_type VARCHAR(30),
  ref_id BIGINT,
  dedupe_key VARCHAR(100),
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_notif_dedupe ON notifications(target_role, dedupe_key) WHERE dedupe_key IS NOT NULL;
CREATE INDEX idx_notif_role ON notifications(target_role, created_at DESC);
