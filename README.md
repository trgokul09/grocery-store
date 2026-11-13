# Smart Grocery Store Management System

Full-stack, production-oriented system for a neighbourhood grocery shop.

* **Owner** – mobile-first dashboard (sales, cash/UPI split, profit, supplier dues, stock value, alerts), P&L, audit log, staff & supplier approval.
* **Manager** – inventory, purchase orders, suppliers, expenses, reconciliation.
* **Staff (2–3 shift workers)** – fast touch/keyboard POS, shift open/close with blind cash count, stock receiving, authorised expenses.

| Layer | Stack |
|---|---|
| Frontend | React 18 (Vite), Tailwind CSS, Lucide, React Router, Axios, Recharts, SheetJS |
| Backend | Java 21, Spring Boot 3.3, Spring Security + JWT, Spring Data JPA/Hibernate, Bean Validation, Flyway, Apache POI, Maven |
| Database | PostgreSQL 15 |

## Quick start

### Option A – Docker (everything)
```bash
docker compose up --build
# app:  http://localhost:8081     api: http://localhost:8080
```
### Option B – local development
```bash
# 1. database
docker run -d --name grocery-db -e POSTGRES_DB=grocery -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:15-alpine
# 2. backend  (Java 21 + Maven)
cd backend && mvn spring-boot:run
# 3. frontend (Node 18+)
cd frontend && npm install && npm run dev      # http://localhost:5173  (proxies /api -> :8080)
```
**First login:** `owner` / `ChangeMe@123` (override with `APP_OWNER_PASSWORD`). Change it from the sidebar, then create Manager/Staff logins under **Staff**.

Config (env vars): `DB_URL DB_USER DB_PASSWORD JWT_SECRET (>=32 chars) APP_OWNER_PASSWORD APP_TIMEZONE (default Asia/Kolkata) CORS_ORIGINS`.

### Suggested first-use walkthrough
1. Owner: add categories → add products (or **Products → Import Excel/CSV**; download the template first).
2. Suppliers → add supplier → *Supplied products* (price + lead time; mark preferred).
3. Purchase Orders → New PO → Submit & approve → Receive (staff enter received/damaged; owner/manager can pay now).
4. Staff: **POS** → open shift → scan/search → charge (cash / UPI / split) → close shift with counted cash.
5. Owner phone: Dashboard (Today / Week / Month / Custom).

## How the critical rules are implemented
| Rule | Implementation |
|---|---|
| Stock changes come from transactions | Only `InventoryService.apply()` mutates stock (sales, PO receipts, adjustments, returns, opening stock). Product edit ignores stock. |
| Manual overrides | `POST /api/inventory/adjust`: increase, decrease, damaged/expired, customer return, **audit (set to counted qty)** – reason mandatory, OWNER/MANAGER only. |
| Bulk import | `.xlsx`/`.csv`, template download, SheetJS preview in browser, server validates *every* row; any error ⇒ **nothing** saved and a row-level error report is returned. One DB transaction. |
| No hard deletes | Products, categories, suppliers, users are deactivated (`is_active=false`); history is preserved. No DELETE of financial rows exists. |
| Concurrency | `@Lock(PESSIMISTIC_WRITE)` row lock on the product in every stock mutation (5 s lock timeout), products locked in ascending-id order in checkout (no deadlocks), `@Version` on products, PO/supplier rows locked when receiving/paying. |
| Atomic checkout | One `@Transactional` method: sale + items + payments + stock deduction + movements + COGS + audit; any failure rolls back all. Payments must equal the bill total. |
| `stock_movements` | previous/new stock, type (SALE, PURCHASE_RECEIPT, MANUAL_ADD, MANUAL_DEDUCT, DAMAGE, RETURN), ref type/id, user, reason, timestamp. |
| Supplier ledger | `Outstanding = Opening + Purchases + Debit adj − Payments − Credit adj`, computed from `supplier_ledger`. Scenarios: pay in full / credit / part-payment at receipt, or later via *Record payment*. Overpayment rejected. |
| PO flow | DRAFT → PENDING_APPROVAL → APPROVED → PARTIALLY_RECEIVED → RECEIVED / CANCELLED. Staff see only deliverable POs. Damaged units are not stocked or billed. |
| Reorder | `ROP = avgDailySales(30d) × leadTime + safetyStock(min level)`; grouped per supplier; owner converts a group to a PO in one click. |
| P&L | `Net revenue = Σ(subtotal − discount)`, `Gross = revenue − COGS` (cost snapshotted per sale line), `Operating = Gross − expenses`. Purchases are **not** expenses ⇒ no double counting.  |
| Shift rules | Staff must enter opening cash to open a shift, and can sign out only after closing it with their counted closing cash (`POST /api/auth/logout` is rejected while a staff shift is open). |
| Cash reconciliation | `Expected = opening + cash sales − cash expenses − cash paid to suppliers`; variance computed on close; staff do a *blind* count; non-zero variance notifies owner/manager. |
| Audit | `audit_logs` written inside the business transaction with old/new JSON; a DB trigger blocks UPDATE/DELETE (immutable). Owner-only viewer. |
| Notifications | Low stock (on crossing the min level), expiring/expired (daily 07:00 + on start), PO awaiting approval, shift variance. Frontend polls every 30 s. |
| Role security | Enforced server-side with `@PreAuthorize` + data scoping (e.g. staff only see their own sales/shifts/expenses of today). |

## API overview (all under `/api`, `Authorization: Bearer <jwt>`)
`auth/login|me|change-password` · `users` · `categories` · `products` (+`/import`, `/import/template`, `/barcode/{code}`) · `inventory/adjust|movements` ·
`suppliers` (+`/{id}/ledger|payments|adjustments|products|approve`) · `purchase-orders` (+`submit|approve|reject|cancel|receive`) · `reorder/recommendations|convert` ·
`sales/checkout`, `sales`, `pos/quick-items` · `expenses` · `shifts` (+`open|close|current`) · `dashboard`, `finance/pnl` · `notifications` · `audit-logs`

## Tests
Integration tests use Testcontainers (real PostgreSQL – needs Docker):
```bash
cd backend && mvn verify
```
Covered: auth/role enforcement, soft delete, duplicate SKU, opening-stock movement, checkout + shift reconciliation, rollback on failure,
**25 concurrent buyers of 10 units ⇒ exactly 10 succeed, stock ends at 0**, full PO → receive → supplier ledger flow.

## Design notes / known simplifications
* **No tax/GST is added**: the customer pays the plain selling price (minus any discount).
* Cost basis for COGS is the *latest* purchase price (updated on each receipt) – not FIFO/weighted-average.
* Customer-return **stock** is supported (Adjust → Customer return); a refund/credit-note financial flow is not modelled.
* Real-time alerts are poll-based (30 s) rather than WebSocket/SSE.
* Single store, single currency (₹), no multi-branch.

> **Note:** this codebase was assembled without a Java toolchain available, so the backend has not been compiled or run by the author;
> the frontend was build-verified. Run `mvn verify` first and report/fix anything it surfaces – expect at most small compile-level fixes.

## Deploying online (Render, one link for everyone)
The root `Dockerfile` builds the React app and the Spring Boot API into **one container** (the API serves the website), so there are no CORS or API-URL settings.
`render.yaml` describes the container + a PostgreSQL database. Steps: push this folder to GitHub → Render → New → Blueprint → select the repo →
enter `APP_OWNER_PASSWORD` → Apply. Open the `https://grocery-app-xxxx.onrender.com` link and log in as `owner`.
Free-tier notes: the app sleeps after ~15 min idle (first load is slow) and the free database expires after ~30 days — use paid plans for real use.
