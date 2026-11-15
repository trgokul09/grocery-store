-- Phase 2: Employee Management, Attendance, Leave & Payroll Schema

CREATE SEQUENCE IF NOT EXISTS emp_code_seq START 1;
CREATE SEQUENCE IF NOT EXISTS payslip_seq START 1;

CREATE TABLE IF NOT EXISTS payroll_settings (
  id BIGINT PRIMARY KEY DEFAULT 1,
  standard_working_days INT NOT NULL DEFAULT 26 CHECK (standard_working_days BETWEEN 1 AND 31),
  standard_daily_hours NUMERIC(4,2) NOT NULL DEFAULT 8.00 CHECK (standard_daily_hours > 0),
  grace_late_minutes INT NOT NULL DEFAULT 15 CHECK (grace_late_minutes >= 0),
  overtime_multiplier NUMERIC(4,2) NOT NULL DEFAULT 1.00 CHECK (overtime_multiplier >= 1.00),
  default_annual_paid_leaves INT NOT NULL DEFAULT 12 CHECK (default_annual_paid_leaves >= 0),
  deduct_absent_automatically BOOLEAN NOT NULL DEFAULT FALSE,
  auto_convert_absent_to_paid_leave BOOLEAN NOT NULL DEFAULT TRUE,
  free_absent_days_allowed NUMERIC(4,1) NOT NULL DEFAULT 0.0 CHECK (free_absent_days_allowed >= 0),
  late_days_threshold INT NOT NULL DEFAULT 3 CHECK (late_days_threshold >= 0),
  late_penalty_per_day NUMERIC(10,2) NOT NULL DEFAULT 0.00 CHECK (late_penalty_per_day >= 0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO payroll_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS employee_profiles (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL UNIQUE REFERENCES users(id),
  employee_code VARCHAR(30) NOT NULL UNIQUE,
  phone VARCHAR(30),
  email VARCHAR(120),
  designation VARCHAR(80) NOT NULL DEFAULT 'Store Associate',
  joining_date DATE NOT NULL DEFAULT CURRENT_DATE,
  employment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  assigned_shift VARCHAR(30) NOT NULL DEFAULT 'GENERAL',
  shift_start_time VARCHAR(5) NOT NULL DEFAULT '09:00',
  shift_end_time VARCHAR(5) NOT NULL DEFAULT '18:00',
  address VARCHAR(255),
  salary_type VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
  basic_salary NUMERIC(12,2) NOT NULL DEFAULT 15000.00 CHECK (basic_salary >= 0),
  daily_wage NUMERIC(10,2) NOT NULL DEFAULT 576.92 CHECK (daily_wage >= 0),
  overtime_hourly_rate NUMERIC(10,2) NOT NULL DEFAULT 100.00 CHECK (overtime_hourly_rate >= 0),
  paid_leave_quota INT NOT NULL DEFAULT 12 CHECK (paid_leave_quota >= 0),
  bank_account_no VARCHAR(50),
  ifsc_code VARCHAR(20),
  upi_id VARCHAR(80),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS employee_attendance (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  attendance_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL,
  leave_type VARCHAR(20),
  check_in TIMESTAMPTZ,
  check_out TIMESTAMPTZ,
  is_late BOOLEAN NOT NULL DEFAULT FALSE,
  late_minutes INT NOT NULL DEFAULT 0,
  worked_hours NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (worked_hours >= 0),
  overtime_hours NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (overtime_hours >= 0),
  notes VARCHAR(255),
  recorded_by BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, attendance_date)
);
CREATE INDEX IF NOT EXISTS idx_emp_attendance_date ON employee_attendance(attendance_date, user_id);
CREATE INDEX IF NOT EXISTS idx_emp_attendance_user_date ON employee_attendance(user_id, attendance_date DESC);

CREATE TABLE IF NOT EXISTS leave_requests (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  leave_type VARCHAR(20) NOT NULL,
  is_paid BOOLEAN NOT NULL DEFAULT TRUE,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  total_days NUMERIC(4,1) NOT NULL CHECK (total_days > 0),
  reason VARCHAR(300) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  reviewed_by BIGINT REFERENCES users(id),
  reviewed_at TIMESTAMPTZ,
  review_note VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_leave_requests_user ON leave_requests(user_id, start_date DESC);
CREATE INDEX IF NOT EXISTS idx_leave_requests_dates ON leave_requests(start_date, end_date);

CREATE TABLE IF NOT EXISTS salary_advances (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
  deducted_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (deducted_amount >= 0),
  advance_date DATE NOT NULL,
  method VARCHAR(10) NOT NULL,
  reason VARCHAR(255),
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  expense_id BIGINT REFERENCES expenses(id),
  created_by BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_salary_advances_user ON salary_advances(user_id, status);

CREATE TABLE IF NOT EXISTS payroll_records (
  id BIGSERIAL PRIMARY KEY,
  payslip_no VARCHAR(35) NOT NULL UNIQUE,
  user_id BIGINT NOT NULL REFERENCES users(id),
  payroll_month VARCHAR(7) NOT NULL,
  salary_type VARCHAR(20) NOT NULL,
  basic_salary NUMERIC(12,2) NOT NULL,
  daily_wage NUMERIC(10,2) NOT NULL,
  working_days INT NOT NULL,
  present_days NUMERIC(4,1) NOT NULL DEFAULT 0,
  half_days INT NOT NULL DEFAULT 0,
  paid_leave_days NUMERIC(4,1) NOT NULL DEFAULT 0,
  unpaid_leave_days NUMERIC(4,1) NOT NULL DEFAULT 0,
  absent_days NUMERIC(4,1) NOT NULL DEFAULT 0,
  late_days INT NOT NULL DEFAULT 0,
  payable_days NUMERIC(4,1) NOT NULL DEFAULT 0,
  overtime_hours NUMERIC(6,2) NOT NULL DEFAULT 0,
  earned_basic NUMERIC(12,2) NOT NULL DEFAULT 0,
  overtime_pay NUMERIC(12,2) NOT NULL DEFAULT 0,
  bonus NUMERIC(12,2) NOT NULL DEFAULT 0,
  incentives NUMERIC(12,2) NOT NULL DEFAULT 0,
  leave_deduction NUMERIC(12,2) NOT NULL DEFAULT 0,
  advance_deduction NUMERIC(12,2) NOT NULL DEFAULT 0,
  other_deductions NUMERIC(12,2) NOT NULL DEFAULT 0,
  gross_salary NUMERIC(12,2) NOT NULL DEFAULT 0,
  net_salary NUMERIC(12,2) NOT NULL CHECK (net_salary >= 0),
  paid_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
  pending_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (pending_amount >= 0),
  payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  last_payment_method VARCHAR(10),
  last_paid_at TIMESTAMPTZ,
  notes VARCHAR(255),
  generated_by BIGINT REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, payroll_month)
);
CREATE INDEX IF NOT EXISTS idx_payroll_month ON payroll_records(payroll_month, user_id);

CREATE TABLE IF NOT EXISTS payroll_payments (
  id BIGSERIAL PRIMARY KEY,
  payroll_id BIGINT NOT NULL REFERENCES payroll_records(id) ON DELETE CASCADE,
  amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
  method VARCHAR(10) NOT NULL,
  expense_id BIGINT REFERENCES expenses(id),
  note VARCHAR(255),
  paid_by BIGINT REFERENCES users(id),
  paid_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_payroll_payments_payroll ON payroll_payments(payroll_id, paid_at DESC);
