package com.grocery.ai;

import com.grocery.ai.AiDtos.NavAction;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Retrieval-Augmented Generation (RAG) Knowledge Base for Smart Grocery Store Management System.
 * Contains structured documentation for all 19 application modules, step-by-step user guides,
 * accounting/analytics/payroll formulas, and role-based permission matrices.
 */
@Component
public class GroceryKnowledgeBase {

    public record KnowledgeArticle(
        String id,
        String title,
        List<String> keywords,
        String summary,
        List<String> steps,
        String formulasAndRules,
        List<NavAction> navActions,
        List<String> allowedRoles
    ) {}

    private final List<KnowledgeArticle> articles = buildArticles();

    public List<KnowledgeArticle> search(String query, String userRole, int maxResults) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.toLowerCase(Locale.ROOT);
        String[] tokens = q.replaceAll("[^a-z0-9\\s]", " ").split("\\s+");

        record Scored(KnowledgeArticle article, int score) {}
        List<Scored> scored = new ArrayList<>();

        for (KnowledgeArticle a : articles) {
            int score = 0;
            String titleLower = a.title().toLowerCase(Locale.ROOT);
            String summaryLower = a.summary().toLowerCase(Locale.ROOT);

            if (q.contains(titleLower)) score += 25;
            for (String kw : a.keywords()) {
                String kwLower = kw.toLowerCase(Locale.ROOT);
                if (q.contains(kwLower)) {
                    score += (kwLower.contains(" ") ? 18 : 10);
                }
            }
            for (String token : tokens) {
                if (token.length() < 3) continue;
                if (Set.of("how", "what", "where", "can", "the", "for", "and", "with", "does", "tell", "about", "explain").contains(token)) continue;
                if (titleLower.contains(token)) score += 8;
                for (String kw : a.keywords()) {
                    if (kw.toLowerCase(Locale.ROOT).contains(token)) score += 5;
                }
                if (summaryLower.contains(token)) score += 3;
            }
            if (score > 0) {
                scored.add(new Scored(a, score));
            }
        }

        return scored.stream()
            .sorted((a, b) -> Integer.compare(b.score(), a.score()))
            .limit(maxResults)
            .map(Scored::article)
            .collect(Collectors.toList());
    }

    public String formatArticleResponse(KnowledgeArticle a, String userRole) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(a.title()).append("\n\n");
        sb.append(a.summary()).append("\n\n");
        if (!a.steps().isEmpty()) {
            sb.append("**Step-by-Step Instructions:**\n");
            for (int i = 0; i < a.steps().size(); i++) {
                sb.append(i + 1).append(". ").append(a.steps().get(i)).append("\n");
            }
            sb.append("\n");
        }
        if (a.formulasAndRules() != null && !a.formulasAndRules().isBlank()) {
            sb.append("**Business Rules & Formulas:**\n").append(a.formulasAndRules()).append("\n\n");
        }
        sb.append("**Role Access:** Allowed for `").append(String.join("`, `", a.allowedRoles())).append("`.");
        if (!a.allowedRoles().contains(userRole)) {
            sb.append(" *(Note: Your current role `").append(userRole).append("` has restricted access to this screen.)*");
        }
        return sb.toString();
    }

    public String getFullSystemOverview() {
        return """
            **Smart Grocery Store Management & Accounting System** is a full-stack enterprise platform with 19 integrated modules:
            1. **Sales Analytics (`/analytics`)**: Product rankings (quantity, revenue, profit), sales velocity (daily/weekly/monthly units), stock coverage days, category analytics, daily/weekly/monthly/yearly trends, cashier & payment breakdown, and Excel/PDF exports.
            3. **POS Billing (`/pos`)**: Fast barcode/keyboard checkout (`F2` search, `F4` hold bill, `F8` recall, `F9` complete sale), split payments (CASH/UPI/CARD), and thermal invoice printing.
            4. **Sales History (`/sales`)**: Completed invoices, line-item COGS/margins, and receipt reprints.
            5. **Products & Categories (`/products`)**: SKU catalog, barcode generation, purchase/selling prices, unit types (`PCS`, `KG`, `GRAM`, `LITRE`, `PACK`), min stock levels, expiry dates, and Category Management.
            6. **Inventory (`/inventory`)**: Stock adjustments (`RESTATEMENT`, `DAMAGE`, `EXPIRY`, `SHRINKAGE`), full Stock Movement Ledger, low-stock and expiring batch alerts.
            7. **Purchase Orders (`/purchase-orders`) & Smart Reorder (`/reorder`)**: Automated reorder point calculation based on 30-day velocity + lead time, PO creation, and goods receipt (`GRN`) with weighted-average cost update.
            8. **Suppliers (`/suppliers`)**: Vendor directory, supplier-product catalog & lead times, supplier ledger (`PURCHASE`, `PAYMENT`), and outstanding payable tracking.
            9. **Expenses (`/expenses`)**: Store operating expenses by category (`RENT`, `ELECTRICITY`, `SALARY`, `TRANSPORT`, `MAINTENANCE`, `MISC`) paid via `CASH`, `UPI`, or `CARD`.
            10. **Cash Shift Management (`/shifts`)**: Mandatory shift opening float before POS billing, live expected cash tracking, shift closing with physical cash count and variance calculation.
            11. **HR, Attendance, Leave & Payroll (`/employees`)**: Employee profiles, shift assignments, self check-in/out with late & overtime detection, monthly attendance calendar, paid/unpaid leave workflow, configurable payroll engine, salary advances, partial/full salary disbursement (auto-linked to `Expenses`), and printable PDF payslips.
            12. **Notifications (`/notifications`), Staff Logins (`/users`), & Audit Log (`/audit`)**: Real-time operational alerts, RBAC account management (`OWNER`, `MANAGER`, `STAFF`), and immutable audit trail.
            """;
    }

    private List<KnowledgeArticle> buildArticles() {
        List<KnowledgeArticle> list = new ArrayList<>();

        list.add(new KnowledgeArticle(
            "HOW_TO_ADD_PRODUCT",
            "How to Add or Edit a Product & Category",
            List.of("add a product", "create product", "new product", "edit product", "add item", "barcode", "sku", "category", "add category", "manage categories", "products module"),
            "The **Products & Categories** module (`/products`) manages your store's SKU catalog, pricing, stock thresholds, barcodes, and categories.",
            List.of(
                "Navigate to **Products** (`/products`) from the left sidebar.",
                "To create a category first (optional), click **Categories** in the top-right toolbar, enter the category name and description, and click **Add**.",
                "Click **+ New Product** in the top-right corner of the Products page.",
                "Enter the **Product Name**, select a **Category**, and choose the **Unit** (`PCS`, `KG`, `GRAM`, `LITRE`, `ML`, `PACK`, or `BOX`).",
                "Leave **SKU** or **Barcode** blank to auto-generate them, or scan/type your manufacturer barcode.",
                "Enter **Purchase Price (Cost)** and **Selling Price** (Selling Price must be >= Purchase Price for positive margin).",
                "Set **Initial Stock** (for new products), **Min Stock Level** (triggers Low Stock alerts), and **Expiry Date** (if perishable).",
                "Click **Save** to add the product to the catalog and record its opening stock ledger entry."
            ),
            "- **Auto SKU/Barcode**: If left blank, the system generates a unique SKU and 12-digit barcode.\n- **Inventory Valuation**: Calculated as `SUM(current_stock * purchase_price)` across active products.\n- **Price Guardrail**: Selling below cost is blocked or warned to protect store gross margin.",
            List.of(new NavAction("Open Products", "/products", "Add or manage products and categories")),
            List.of("OWNER", "MANAGER", "STAFF")
        ));

        list.add(new KnowledgeArticle(
            "HOW_TO_CREATE_PO",
            "How to Create a Purchase Order & Receive Goods (GRN)",
            List.of("create a purchase order", "purchase order", "new po", "receive stock", "restock", "grn", "smart reorder", "order from supplier", "how do i create a purchase order"),
            "The **Purchase Orders** (`/purchase-orders`) and **Smart Reorder** (`/reorder`) modules handle supplier ordering, goods receipt, weighted-average cost updates, and supplier payable ledgers.",
            List.of(
                "Go to **Purchase Orders** (`/purchase-orders`) from the left sidebar (or use **Smart Reorder** `/reorder` to auto-generate draft POs for low-stock items).",
                "Click **+ New PO** in the top-right corner.",
                "Select the **Supplier**, optional **Expected Delivery Date**, and notes.",
                "Add one or more line items by selecting the **Product**, **Ordered Quantity**, and **Unit Cost**.",
                "Click **Create PO** to save the order (Status becomes `DRAFT` or `SENT`).",
                "When the supplier delivers the goods, click **Receive** on the PO row.",
                "Verify or adjust the **Received Quantity**, **Unit Cost**, and optional **Expiry Date** for each item, enter the **Supplier Invoice Number**, and click **Confirm Receipt**."
            ),
            "- **Weighted Average Cost**: Receiving goods automatically recalculates the product's `purchase_price` using weighted-average costing: `((old_stock * old_cost) + (received_qty * new_cost)) / (old_stock + received_qty)`.\n- **Accounting Impact**: Receiving a PO credits the Supplier's payable ledger (`PURCHASE`). It does **not** double-count as an operating expense because inventory cost flows through **COGS** when sold.",
            List.of(
                new NavAction("Open Purchase Orders", "/purchase-orders", "Create and receive supplier purchase orders"),
                new NavAction("Open Smart Reorder", "/reorder", "View automated restock recommendations")
            ),
            List.of("OWNER", "MANAGER", "STAFF")
        ));

        list.add(new KnowledgeArticle(
            "HOW_TO_RECORD_EXPENSE",
            "How to Record a Store Expense",
            List.of("record an expense", "add expense", "log expense", "store expense", "electricity bill", "rent expense", "expenses module", "how do i record an expense"),
            "The **Expenses** module (`/expenses`) tracks all store operating expenses (`RENT`, `ELECTRICITY`, `SALARY`, `TRANSPORT`, `PACKAGING`, `MAINTENANCE`, `MARKETING`, `MISC`) and deducts them from Gross Profit to compute Operating Profit.",
            List.of(
                "Navigate to **Expenses** (`/expenses`) from the sidebar.",
                "Click **+ Add Expense** in the top-right corner.",
                "Select the **Category** (e.g., `RENT`, `ELECTRICITY`, `TRANSPORT`, `MAINTENANCE`, `MISC`).",
                "Enter the **Title/Description**, **Amount (₹)**, **Payment Method** (`CASH`, `UPI`, or `CARD`), and **Expense Date**.",
                "Click **Save** to record the expense."
            ),
            "- **Cash & Drawer Impact**: If `CASH` is selected while you have an open Cash Shift, the expense is automatically tracked in your shift's expected drawer cash calculation.\n- **Ledger Balance**: `CASH` and `UPI` expenses immediately reduce the live Cash/UPI ledger balance on the Dashboard.\n- **P&L Formula**: `Operating Profit = Gross Profit - Operating Expenses`.",
            List.of(new NavAction("Open Expenses", "/expenses", "Record and filter store operating expenses")),
            List.of("OWNER", "MANAGER", "STAFF")
        ));

        list.add(new KnowledgeArticle(
            "HOW_TO_SHIFT",
            "How to Open or Close a Cash Shift",
            List.of("open or close a cash shift", "open a shift", "close a shift", "cash shift", "shift management", "opening cash", "closing cash", "cash drawer", "shifts module", "how do i open", "how do i close"),
            "The **Cash Shift Management** module (`/shifts`) enforces cash drawer accountability for every cashier before they can bill customers at the POS.",
            List.of(
                "Go to **Shifts** (`/shifts`) from the sidebar.",
                "**To Open a Shift**: Enter your **Opening Cash Float (₹)** counted in the drawer and optional notes, then click **Open Shift**. (An active shift is mandatory before completing any POS sale).",
                "**During the Shift**: All POS cash sales and cash expenses are automatically linked to your open shift to compute live **Expected Drawer Cash**.",
                "**To Close a Shift**: Count the physical cash in your drawer, enter the **Actual Closing Cash (₹)** and closing notes on the Shifts page, and click **Close Shift**.",
                "The system automatically computes and logs any **Cash Variance** (`Actual Closing Cash - Expected Cash`). Note: Staff users must close their open shift before signing out."
            ),
            "- **Expected Cash Formula**: `Expected Cash = Opening Float + Cash Sales in Shift - Cash Expenses in Shift`.\n- **Variance Formula**: `Variance = Actual Closing Cash - Expected Cash` (Positive = Surplus, Negative = Shortage).",
            List.of(
                new NavAction("Open Shifts", "/shifts", "Open or close your cash drawer shift"),
                new NavAction("Open POS Billing", "/pos", "Go to POS checkout")
            ),
            List.of("OWNER", "MANAGER", "STAFF")
        ));

        list.add(new KnowledgeArticle(
            "HOW_TO_ADD_EMPLOYEE",
            "How to Add or Edit an Employee",
            List.of("add an employee", "create employee", "new employee", "hire staff", "employee management", "deactivate employee", "edit employee", "staff profile", "how do i add an employee"),
            "The **HR & Payroll** module (`/employees`) manages employee profiles, login credentials, shift timings, designations, and salary structures without duplicating user accounts.",
            List.of(
                "Navigate to **HR & Payroll** (`/employees`) from the left sidebar and select the **Employees** tab.",
                "Click **+ Add Employee** in the top-right corner.",
                "Enter login details: **Full Name**, **Username**, **Initial Password** (min 8 chars), and **Role** (`STAFF`, `MANAGER`, or `OWNER`).",
                "Fill in HR profile details: **Designation** (e.g., Cashier, Store Supervisor), **Phone**, **Email**, **Joining Date**, **Assigned Shift** (`MORNING`, `EVENING`, `FULL_DAY`), and **Shift Start/End Time**.",
                "Configure compensation (Owner only): **Salary Type** (`MONTHLY` or `DAILY`), **Monthly Basic Salary**, **Daily Wage**, **Overtime Hourly Rate**, **Annual Paid Leave Quota**, and bank/UPI payout details.",
                "Click **Create Employee** to provision both the user login and HR profile simultaneously.",
                "To edit or deactivate an employee, use the **Edit** or **Activate/Deactivate** action buttons in the Employees table."
            ),
            "- **Unified Identity**: Every employee profile is linked 1-to-1 with the `users` table so employees can log in with their own credentials to check in/out and view their payslips.\n- **Role Security**: Only `OWNER` can view or modify confidential salary figures.",
            List.of(new NavAction("Open HR & Payroll", "/employees", "Manage employees, attendance, leaves, and payroll")),
            List.of("OWNER", "MANAGER")
        ));

        list.add(new KnowledgeArticle(
            "HOW_TO_MARK_ATTENDANCE",
            "How to Mark Attendance (Check-In / Check-Out & Manual Register)",
            List.of("mark attendance", "check in", "check out", "attendance", "late arrival", "overtime", "attendance calendar", "how do i mark attendance"),
            "The **Attendance Management** tab inside **HR & Payroll** (`/employees`) supports both authenticated self-service Check-In/Check-Out and manager/owner daily attendance register updates.",
            List.of(
                "Navigate to **HR & Payroll** (`/employees`).",
                "**For Self Check-In / Check-Out (All Employees)**: Use the top **Self Attendance Bar** at the top of the `/employees` page. Click **Check In Now** when starting work and **Check Out Now** when finishing.",
                "The system automatically compares your check-in time against your assigned shift start time + grace period to detect **Late Arrivals**, and compares worked hours against standard daily hours to calculate **Overtime Hours**.",
                "**For Owner / Manager Manual Register**: Switch to the **Attendance** tab in `/employees`, select the **Date**, and click **Mark / Edit** on any employee row.",
                "Choose the status (`PRESENT`, `ABSENT`, `HALF_DAY`, `LEAVE`, or `WEEK_OFF`), adjust Check-In/Out times or Overtime Hours if needed, and click **Save Attendance**.",
                "Switch to the **Monthly Calendar** tab to inspect the full-month day-by-day grid (`P`, `A`, `HD`, `PL`, `UL`) and attendance percentage for every employee."
            ),
            "- **Duplicate Prevention**: Employees cannot check in twice on the same day or check out before checking in.\n- **Attendance Percentage Formula**: `((Present Days + 0.5 * Half Days + Paid Leave Days) / Working Days in Period) * 100`.",
            List.of(new NavAction("Open Attendance", "/employees", "Check in/out or manage the daily attendance register")),
            List.of("OWNER", "MANAGER", "STAFF")
        ));

        list.add(new KnowledgeArticle(
            "HOW_TO_APPROVE_LEAVE",
            "How to Apply for Leave & Approve/Reject Leave Requests",
            List.of("approve leave", "apply leave", "leave request", "leave management", "paid leave", "unpaid leave", "leave balance", "how do i approve leave", "reject leave"),
            "The **Leaves** tab inside **HR & Payroll** (`/employees`) handles employee leave applications, paid/unpaid quota tracking, and manager/owner approvals.",
            List.of(
                "Go to **HR & Payroll** (`/employees`) and click the **Leaves** tab.",
                "**To Apply for Leave**: Click **+ Apply Leave**, choose the **Leave Type** (`PAID`, `SICK`, `CASUAL`, or `UNPAID`), select **Start Date** and **End Date** (and optional **Half Day** toggle), enter the **Reason**, and click **Submit Leave Request**.",
                "**To Approve or Reject Leave (Owner/Manager)**: Locate the `PENDING` leave request in the Leave Requests table.",
                "Click **Approve** or **Reject**, optionally enter a review note, and confirm.",
                "When a leave is **APPROVED**, the system automatically populates `LEAVE` (`PAID` or `UNPAID`) entries in the employee's daily attendance calendar for those dates and updates their **Leave Balance**."
            ),
            "- **Quota Guardrail**: Paid leave requests (`PAID`, `SICK`, `CASUAL`) are validated against the employee's remaining annual paid leave quota.\n- **Automatic Attendance Sync**: Approved leaves automatically sync to `employee_attendance` so payroll never deducts salary for approved paid leaves.",
            List.of(new NavAction("Open Leave Management", "/employees", "Apply for leave or review pending requests")),
            List.of("OWNER", "MANAGER", "STAFF")
        ));

        list.add(new KnowledgeArticle(
            "HOW_TO_GENERATE_PAYROLL",
            "How to Configure Payroll Rules, Generate Monthly Payroll & Pay Salaries",
            List.of("generate payroll", "run payroll", "pay salary", "payslip", "salary advance", "payroll rules", "payroll settings", "deductions", "bonus", "how do i generate payroll"),
            "The **Payroll & Payslips** tab inside **HR & Payroll** (`/employees`) automates monthly salary calculation, attendance/leave policy rules, salary advances, partial/full salary disbursements, and PDF payslip generation.",
            List.of(
                "Navigate to **HR & Payroll** (`/employees`) and select the **Payroll & Payslips** tab (Owner access).",
                "**Configure Payroll Rules (Optional)**: Switch to the **Payroll Rules** tab to set Standard Working Days (e.g., 26 or 30), Standard Daily Hours (8h), Grace Late Minutes (15m), Overtime Multiplier (1.5x), Auto-Convert Absent to Paid Leave, Free Absent Days Allowed, and Late Penalty rules.",
                "**Generate Monthly Payroll**: In the **Payroll & Payslips** tab, select the **Payroll Month** (e.g., `YYYY-MM`) and click **Generate / Recalculate Payroll**.",
                "**Adjust Bonus / Deductions**: Click **Adjust** on any employee's payroll row to add a **Bonus**, **Incentives**, or adjust **Advance/Leave Deductions**.",
                "**Disburse Salary (Full or Partial)**: Click **Pay** on the payroll row, enter the **Amount** (supports partial or full payment) and **Payment Method** (`CASH`, `UPI`, or `CARD`), and confirm. Every salary payment automatically records a linked `SALARY` entry in the **Expenses** module.",
                "**View / Print PDF Payslip**: Click **Payslip** on any row to open the formatted payslip modal and click **Print / Save PDF**."
            ),
            "- **Net Salary Formula**: `Net Salary = Earned Basic + Overtime Pay + Bonus + Incentives - Leave Deduction - Advance Deduction - Other Deductions`.\n- **Duplicate Payment Protection**: Once a payroll record reaches `PAID` (`pendingAmount = 0`), additional payments are blocked.",
            List.of(new NavAction("Open Payroll & Payslips", "/employees", "Generate monthly payroll, disburse salaries, and print payslips")),
            List.of("OWNER", "MANAGER", "STAFF")
        ));

        list.add(new KnowledgeArticle(
            "HOW_TO_VIEW_ANALYTICS",
            "How to View Sales Analytics & Export Excel/PDF Reports",
            List.of("view sales analytics", "sales analytics", "analytics dashboard", "product velocity", "best selling", "least selling", "category analytics", "export excel", "export pdf", "how do i view sales analytics"),
            "The **Sales Analytics Dashboard** (`/analytics`) provides deep product, category, trend, cashier, and velocity intelligence with interactive filters and Excel/PDF exports.",
            List.of(
                "Click **Sales Analytics** (`/analytics`) in the left navigation sidebar.",
                "Use the **Date Range Filter Bar** at the top to switch between `Today`, `Yesterday`, `Last 7 days`, `Last 30 days`, `This month`, `Last month`, or a `Custom range`.",
                "Optionally filter by a specific **Category** or **Product** using the dropdown selectors.",
                "Switch between the 4 analytical tabs: **Overview & Trends** (Daily/Weekly/Monthly/Yearly charts, Payment & Cashier breakdown, Peak Days/Hours), **Product Rankings** (Top 10 by Qty/Revenue/Profit, Bottom 10, Declining, Zero-Sales), **Category Analytics** (Category revenue/profit share & over-time comparison), and **Velocity & Slow Stock** (Daily/Weekly/Monthly velocity, Stock Coverage Days, Slow-Moving items).",
                "Click **Export Excel** to download a multi-section `.xls` workbook or **Export PDF** to print/save a formatted analytical report."
            ),
            "- **Product Sales Velocity**: `Quantity Sold / Available Selling Days in Period`.\n- **Stock Coverage Days**: `Current Stock / Avg Daily Units Sold`.\n- **Gross Profit**: `Net Revenue (Subtotal - Discount) - COGS`.",
            List.of(new NavAction("Open Sales Analytics", "/analytics", "Explore product rankings, velocity, trends, and exports")),
            List.of("OWNER", "MANAGER")
        ));

        list.add(new KnowledgeArticle(
            "HOW_TO_POS_BILLING",
            "How to Use POS Billing & Keyboard Shortcuts",
            List.of("pos billing", "create bill", "checkout", "make a sale", "hold bill", "split payment", "barcode scanner", "keyboard shortcuts", "invoice", "pos"),
            "The **POS Billing** screen (`/pos`) is designed for high-speed retail checkout with barcode scanners, keyboard shortcuts, hold/recall bills, and split tenders.",
            List.of(
                "First ensure you have an **OPEN Cash Shift** in **Shifts** (`/shifts`).",
                "Open **POS Billing** (`/pos`). Press `F2` (or click the search bar) to search by product name, SKU, or scan a barcode.",
                "Adjust quantities (`+`/`-`) or line-item discounts in the cart.",
                "To pause a customer's checkout, press `F4` (**Hold Bill**). Press `F8` to recall a held bill.",
                "Select payment mode (`CASH`, `UPI`, `CARD`, or **Split Payment** across multiple methods).",
                "Press `F9` or click **Complete Sale** to finalize the transaction, deduct stock atomically, and print the receipt."
            ),
            "- **Atomic Inventory & COGS**: Completing a sale deducts `products.current_stock`, writes a `SALE` entry to `stock_movements`, and snapshots each item's `purchase_price` into `sale_items.cost_price` for exact historical COGS.",
            List.of(new NavAction("Open POS Billing", "/pos", "Go to Point of Sale checkout")),
            List.of("OWNER", "MANAGER", "STAFF")
        ));

        list.add(new KnowledgeArticle(
            "ACCOUNTING_AND_PNL_RULES",
            "Accounting, Profit & Loss (P&L) & Ledger Formulas",
            List.of("accounting", "profit formula", "how is profit calculated", "net profit", "operating profit", "gross profit", "cogs", "cash balance", "upi balance", "p&l", "financial report"),
            "The **Accounting & Finance Engine** (`FinanceService` and **Sales Analytics** `/analytics`) calculates real-time financial metrics directly from PostgreSQL transaction ledgers without double-counting.",
            List.of(
                "Open **Sales Analytics** (`/analytics`) to view real-time revenue, COGS, gross profit, margin %, and product/category trends.",
                "Select `Today`, `Yesterday`, `Last 7 days`, `Last 30 days`, `This month`, `Last month`, or a `Custom` date range.",
                "Ask **GroceryGPT** for a live P&L summary (`What is my total profit?`) or click **Download P&L CSV** / **Export Excel** / **Export PDF**."
            ),
            "- **Net Revenue** = `SUM(subtotal - discount_total)`\n- **COGS (Cost of Goods Sold)** = `SUM(quantity * cost_price_at_sale)`\n- **Gross Profit** = `Net Revenue - COGS`\n- **Operating Profit (Net Profit)** = `Gross Profit - Operating Expenses`\n- **Cash / UPI Ledger Balance** = `All-Time Sales Collected (by method) - Expenses Paid (by method) - Supplier Payments (by method)`\n- **Supplier Payable Balance** = `Opening Balance + Purchases (GRN) + Debit Adjustments - Supplier Payments`",
            List.of(new NavAction("Open Sales Analytics", "/analytics", "View revenue, profit, margins, and export Excel/PDF reports")),
            List.of("OWNER", "MANAGER")
        ));

        list.add(new KnowledgeArticle(
            "ROLES_AND_PERMISSIONS",
            "User Roles, Authentication & Security Permissions",
            List.of("roles", "permissions", "access control", "what can staff do", "what can manager do", "owner permissions", "rbac", "security", "change password", "audit log"),
            "The application enforces strict JWT authentication and Role-Based Access Control (`OWNER`, `MANAGER`, `STAFF`) at both the Spring Security API layer and React UI.",
            List.of(
                "**OWNER**: Full access to all modules including Executive Dashboard, Sales Analytics, P&L Reports, Inventory, Suppliers, HR & Payroll (including confidential salary structures and payroll disbursement), Staff Logins (`/users`), and Audit Logs (`/audit`).",
                "**MANAGER**: Access to Dashboard, Sales Analytics, POS, Sales, Products, Inventory, Purchase Orders, Suppliers, Smart Reorder, Expenses, Shifts, Notifications, and HR Attendance & Leave Approvals. Confidential employee salary figures can be restricted to Owner-only.",
                "**STAFF**: Access to POS Billing, Sales History, Products catalog view, Purchase Orders, Expenses, Shifts, and self-service HR (own Check-In/Check-Out, own Leave Applications, and own Payslips). Staff cannot view store-wide P&L or other employees' attendance/salaries."
            ),
            "- **Shift Sign-Out Guard**: Staff members with an open cash shift must close their shift before signing out.\n- **Audit Trail**: Sensitive mutations are recorded in `audit_logs` (`/audit`).",
            List.of(
                new NavAction("Open Staff Logins", "/users", "Manage user accounts (Owner only)"),
                new NavAction("Open Audit Log", "/audit", "Inspect security audit logs (Owner only)")
            ),
            List.of("OWNER", "MANAGER", "STAFF")
        ));

        return list;
    }
}
