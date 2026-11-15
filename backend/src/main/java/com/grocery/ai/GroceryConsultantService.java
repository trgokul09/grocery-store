package com.grocery.ai;

import com.grocery.ai.AiDtos.NavAction;
import com.grocery.ai.AiDtos.TablePayload;
import com.grocery.ai.BusinessInsightService.ExpenseSpike;
import com.grocery.ai.BusinessInsightService.Snapshot;
import com.grocery.ai.GroceryBusinessToolService.ToolExecutionResult;
import com.grocery.ai.PeriodResolver.Period;
import com.grocery.analytics.AnalyticsDtos.AnalyticsDashboardResponse;
import com.grocery.analytics.AnalyticsDtos.ProductPerformanceDto;
import com.grocery.analytics.AnalyticsService;
import com.grocery.common.StoreClock;
import com.grocery.finance.FinanceService;
import com.grocery.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * Business Consultant engine for GroceryGPT.
 * Combines live PostgreSQL store metrics (via BusinessInsightService / AnalyticsService / FinanceService)
 * with practical retail & financial strategy tailored for Indian grocery stores.
 * Clearly separates actual store data from general retail recommendations, and never invents store numbers.
 */
@Service
public class GroceryConsultantService {

    private static final Logger log = LoggerFactory.getLogger(GroceryConsultantService.class);

    private final BusinessInsightService insight;
    private final AnalyticsService analyticsService;
    private final FinanceService financeService;
    private final NamedParameterJdbcTemplate jdbc;
    private final StoreClock clock;

    public GroceryConsultantService(
        BusinessInsightService insight,
        AnalyticsService analyticsService,
        FinanceService financeService,
        NamedParameterJdbcTemplate jdbc,
        StoreClock clock
    ) {
        this.insight = insight;
        this.analyticsService = analyticsService;
        this.financeService = financeService;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ToolExecutionResult advise(User user, String rawMessage) {
        String q = rawMessage == null ? "" : rawMessage.toLowerCase(Locale.ROOT).trim();
        boolean mgmt = !"STAFF".equals(roleOf(user));

        // 1. Revenue vs Gross Profit vs Net Profit (Conceptual / Financial Literacy)
        if (q.contains("difference between") && (q.contains("revenue") || q.contains("gross profit") || q.contains("net profit"))) {
            return explainFinancialTerms(mgmt);
        }

        // 2. Profit improvement / "Am I making enough profit?" / Profit margin
        if (q.contains("improve") && (q.contains("profit") || q.contains("margin")) ||
            q.contains("enough profit") || q.contains("increase profit") || q.contains("more profit") ||
            q.contains("boost margin")) {
            return adviseOnProfitAndMargin(mgmt);
        }

        // 3. Which products should I focus on selling more?
        if ((q.contains("which product") || q.contains("what product")) &&
            (q.contains("focus") || q.contains("push") || q.contains("sell more") || q.contains("promote"))) {
            return adviseOnProductFocus(mgmt);
        }

        // 4. Average bill value / basket size
        if (q.contains("average bill") || q.contains("basket size") || q.contains("ticket size") || q.contains("order value")) {
            return adviseOnAverageBillValue(mgmt);
        }

        // 5. Reduce expenses
        if ((q.contains("reduce") || q.contains("cut") || q.contains("lower") || q.contains("control")) &&
            (q.contains("expense") || q.contains("cost") || q.contains("spending"))) {
            return adviseOnReducingExpenses(mgmt);
        }

        // 6. Daily savings & Cash flow management
        if (q.contains("cash flow") || q.contains("cashflow") ||
            (q.contains("save") && (q.contains("daily") || q.contains("revenue") || q.contains("how much")))) {
            return adviseOnCashFlowAndSavings(mgmt);
        }

        // 7. Inventory strategy: overstocking, close to expiry, food wastage, summer stocking
        if (q.contains("summer") && (q.contains("stock") || q.contains("product") || q.contains("demand"))) {
            return adviseSummerStocking();
        }
        if (q.contains("overstock")) {
            return adviseAvoidOverstocking(mgmt);
        }
        if (q.contains("close to expiry") || q.contains("near expiry") || q.contains("manage") && q.contains("expir") ||
            q.contains("wastage") || q.contains("waste") || q.contains("spoilage")) {
            return adviseExpiryAndWastage(mgmt);
        }

        // 8. Marketing, loyalty, customer visits, promotions, competing with supermarkets/apps
        if (q.contains("loyalty")) {
            return adviseLoyaltyProgram(mgmt);
        }
        if (q.contains("compete") || q.contains("supermarket") || q.contains("online") || q.contains("zepto") || q.contains("blinkit") || q.contains("bigbasket")) {
            return adviseCompeteWithApps();
        }
        if (q.contains("festival") || q.contains("diwali") || q.contains("holi") || q.contains("festive")) {
            return adviseFestivalOffers();
        }
        if (q.contains("promotional") || q.contains("promotion") || q.contains("creative") && q.contains("idea") ||
            q.contains("attract") || q.contains("more customer") || q.contains("frequently") || q.contains("repeat")) {
            return adviseMarketingAndRetention(mgmt);
        }
        if (q.contains("mistake") && q.contains("avoid")) {
            return adviseCommonMistakes();
        }
        if (q.contains("increase") && q.contains("sale") || q.contains("grow") || q.contains("20%")) {
            return adviseSalesGrowth(mgmt);
        }

        // General business consulting fallback (still grounded!)
        return adviseSalesGrowth(mgmt);
    }

    // ---------------------------------------------------------------------------------------------
    // Personalized Data + Strategy Methods
    // ---------------------------------------------------------------------------------------------

    private ToolExecutionResult adviseOnProfitAndMargin(boolean mgmt) {
        if (!mgmt) {
            return new ToolExecutionResult("CONSULT_PROFIT", false,
                "Profit margins are visible to the store owner and manager, but here are practical ways any store team can protect profit:\n\n" +
                "- **Prevent wastage & expiry:** Rotate older stock to the front (FEFO) every morning.\n" +
                "- **Suggest add-ons at billing:** Recommend high-margin snacks, biscuits, or masala packs to staple buyers.\n" +
                "- **Verify supplier deliveries:** Always count quantities and check expiry dates before signing a GRN.",
                List.of(), List.of(new NavAction("Open POS Billing", "/pos", "Go to checkout")), List.of(),
                List.of("Which products need restocking?", "Which products are expiring?"));
        }

        LocalDate today = clock.today();
        Period mtd = PeriodResolver.thisMonth(today);
        Snapshot cur = insight.snapshot(mtd.from(), mtd.to(), null);
        BigDecimal exp = insight.expensesTotal(mtd.from(), mtd.to());
        BigDecimal net = cur.grossProfit().subtract(exp);

        StringBuilder md = new StringBuilder();
        md.append("### Profit Diagnosis & Action Plan\n\n");
        if (cur.bills() > 0) {
            md.append(String.format("**What your store data shows (%s):**\n" +
                "- **Net Sales:** %s across %d bills\n" +
                "- **Gross Margin:** %s%% (Gross Profit: %s)\n" +
                "- **Operating Expenses:** %s → **Net Profit:** %s\n\n",
                mtd.label(), BusinessInsightService.inr(cur.lineRevenue()), cur.bills(),
                cur.marginPct().toPlainString(), BusinessInsightService.inr(cur.grossProfit()),
                BusinessInsightService.inr(exp), BusinessInsightService.inr(net)));

            if (cur.marginPct().compareTo(new BigDecimal("15")) < 0) {
                md.append("**Diagnosis:** Your gross margin (" + cur.marginPct() + "%) is on the tighter side for a grocery store (healthy kirana/supermarket averages are **16%–22%**). Improving product mix and purchase pricing will move the needle fastest.\n\n");
            } else {
                md.append("**Diagnosis:** Your gross margin (" + cur.marginPct() + "%) is healthy. Focus on growing bill volume and keeping fixed expenses in check.\n\n");
            }
        } else {
            md.append("*No completed sales recorded yet this month — here is a proven profit-improvement framework you can apply right away:*\n\n");
        }

        md.append("**5 Practical Ways to Improve Your Profit:**\n" +
            "1. **Pair low-margin staples with high-margin add-ons:** Rice, atta, sugar, and cooking oil bring footfall (8%–12% margin), while whole spices, dry fruits, namkeen, and personal care earn **25%–40% margin**. Place high-margin snacks right beside staples and at the billing counter.\n" +
            "2. **Buy fast-moving staples in bulk, slow items in small lots:** Negotiate an extra 2%–3% cash discount from suppliers on your top 10 fastest sellers.\n" +
            "3. **Stop silent margin leaks:** Avoid flat blanket discounts; use targeted combo offers (`Buy Atta + Oil, get ₹15 off`) instead.\n" +
            "4. **Clear near-expiry stock early:** Discount items 7 days before expiry by 20%–30% rather than writing off 100% of the cost.\n" +
            "5. **Audit your top expense line weekly:** Check electricity (freezer seals/LEDs) and packaging costs.");

        return new ToolExecutionResult("CONSULT_PROFIT", false, md.toString(), List.of(),
            List.of(new NavAction("Open Sales Analytics", "/analytics", "Inspect product-level margins")),
            List.of(),
            List.of("Which products should I focus on selling more?", "How can I reduce my monthly expenses?", "How can I increase average bill value?"));
    }

    private ToolExecutionResult adviseOnProductFocus(boolean mgmt) {
        if (!mgmt) {
            return new ToolExecutionResult("CONSULT_PRODUCT_FOCUS", false,
                "Focus on keeping everyday staples (milk, bread, eggs, rice, oil) always stocked so customers never leave empty-handed, and keep impulse snacks visible near the counter.",
                List.of(), List.of(), List.of(), List.of("Which products need restocking?"));
        }

        try {
            AnalyticsDashboardResponse dash = analyticsService.getDashboard("LAST_30_DAYS", null, null, null, null, null);
            List<ProductPerformanceDto> stars = dash.topByProfit().stream().filter(p -> p.profit().signum() > 0).limit(5).toList();
            if (!stars.isEmpty()) {
                StringBuilder md = new StringBuilder("### Products to Focus On (Based on Last 30 Days)\n\n");
                md.append("**Your highest profit-generating products right now:**\n");
                List<List<String>> rows = new ArrayList<>();
                for (ProductPerformanceDto p : stars) {
                    md.append(String.format("- **%s** (`%s`): **%s profit** (%s%% margin, %s/day velocity)\n",
                        p.productName(), p.categoryName(), BusinessInsightService.inr(p.profit()), p.marginPct(), p.avgDailyUnits()));
                    rows.add(List.of(p.productName(), p.categoryName(), BusinessInsightService.inr(p.revenue()), BusinessInsightService.inr(p.profit()), p.marginPct() + "%"));
                }
                md.append("\n**Retail Consultant Strategy:**\n" +
                    "1. **Protect stock on these winners:** Never let your top 5 profit makers go out of stock.\n" +
                    "2. **Eye-level placement:** Keep high-margin items (20%+ margin) at eye level and near the checkout counter.\n" +
                    "3. **Bundle with traffic builders:** Pair low-margin staples (milk, rice, atta) with one of these high-margin items as a weekly combo.");

                return new ToolExecutionResult("CONSULT_PRODUCT_FOCUS", false, md.toString(),
                    List.of(new TablePayload("Top Profit Generators (Last 30 Days)", List.of("Product", "Category", "Revenue", "Profit", "Margin %"), rows)),
                    List.of(new NavAction("Open Sales Analytics", "/analytics", "View full product profitability")),
                    List.of(),
                    List.of("How can I increase average bill value?", "Which products have declining sales?", "Do you have any suggestions?"));
            }
        } catch (Exception e) {
            log.debug("Product focus fallback: {}", e.getMessage());
        }

        return new ToolExecutionResult("CONSULT_PRODUCT_FOCUS", false,
            "### Which Products Should You Focus On?\n\n" +
            "*Once you have 1–2 weeks of POS sales, I can rank your exact store winners. Meanwhile, use the **4-Quadrant Grocery Rule**:*\n" +
            "1. **Traffic Builders (Milk, Bread, Eggs, Atta, Oil):** Low margin (8%–12%), but bring customers in daily. Never run out of these.\n" +
            "2. **Profit Makers (Spices, Dry Fruits, Snacks, Personal Care):** High margin (25%–40%). Place at eye level and near billing.\n" +
            "3. **Impulse Buys (Chocolates, Cold Drinks, Gum, Biscuits):** Keep right next to the POS counter.\n" +
            "4. **Private / Loose Staples (Pulses, Grains packed in-store):** Often earn 5%–8% higher margin than branded packs.",
            List.of(), List.of(new NavAction("Open Sales Analytics", "/analytics", "Explore product analytics")), List.of(),
            List.of("How can I increase average bill value?", "How can I improve my profit margin?"));
    }

    private ToolExecutionResult adviseOnAverageBillValue(boolean mgmt) {
        StringBuilder md = new StringBuilder("### How to Increase Average Bill Value (ABV)\n\n");
        if (mgmt) {
            Period mtd = PeriodResolver.thisMonth(clock.today());
            Snapshot s = insight.snapshot(mtd.from(), mtd.to(), null);
            if (s.bills() > 0) {
                BigDecimal target = s.avgBill().multiply(new BigDecimal("1.15")).setScale(0, RoundingMode.CEILING);
                md.append(String.format("**Your current store baseline (%s):** Average bill is **%s** across %d bills. Raising this by just 15%% (to **%s**) adds **15%% revenue without needing a single new customer.**\n\n",
                    mtd.label(), BusinessInsightService.inr(s.avgBill()), s.bills(), BusinessInsightService.inr(target)));
            }
        }
        md.append("**5 Proven Tactics for Indian Grocery Stores:**\n" +
            "1. **Set a Free Home Delivery Minimum:** If your average bill is ₹300, offer free local delivery on orders above ₹499 — customers will add 1–2 extra items to cross the threshold.\n" +
            "2. **Checkout Counter Cross-Selling:** Keep ₹10–₹50 impulse items (chocolates, single-serve coffee, wafers, batteries, tissues) within arm's reach of the POS.\n" +
            "3. **Monthly Ration Combos:** Create a *\"Monthly Kitchen Essentials\"* bundle (5kg Atta + 1L Oil + 1kg Dal + Salt) priced slightly below individual MRP.\n" +
            "4. **Larger Pack Nudges:** Train staff to say, *\"The 1kg pack saves you ₹25 compared to two 500g packs\"* when customers pick small sizes.\n" +
            "5. **Tiered Scratch/Coupon Thresholds:** Give a ₹25 off coupon on the next visit for bills above ₹1,000.");

        return new ToolExecutionResult("CONSULT_ABV", false, md.toString(), List.of(),
            List.of(new NavAction("Open Sales Analytics", "/analytics", "Track Average Order Value")), List.of(),
            List.of("How can I increase my monthly sales by 20%?", "Suggest an interesting customer loyalty program."));
    }

    private ToolExecutionResult adviseOnReducingExpenses(boolean mgmt) {
        StringBuilder md = new StringBuilder("### Practical Ways to Reduce Monthly Store Expenses\n\n");
        if (mgmt) {
            Period mtd = PeriodResolver.thisMonth(clock.today());
            LinkedHashMap<String, BigDecimal> cats = insight.expensesByCategory(mtd.from(), mtd.to());
            List<ExpenseSpike> spikes = insight.expenseSpikes(mtd.from(), mtd.to());
            if (!cats.isEmpty()) {
                var top = cats.entrySet().iterator().next();
                md.append(String.format("**From your store records (%s):** Your largest expense is **%s (%s)**.\n",
                    mtd.label(), BusinessInsightService.pretty(top.getKey()), BusinessInsightService.inr(top.getValue())));
                if (!spikes.isEmpty()) {
                    ExpenseSpike sp = spikes.get(0);
                    md.append(String.format("- *Alert:* **%s** is running higher than your 90-day average (%s vs usual %s).\n",
                        BusinessInsightService.pretty(sp.category()), BusinessInsightService.inr(sp.current()), BusinessInsightService.inr(sp.expected())));
                }
                md.append("\n");
            }
        }
        md.append("**6 Actionable Cost-Cutting Tips:**\n" +
            "1. **Electricity (Usually 15%–25% of store overhead):** Clean refrigerator/deep-freezer condenser coils monthly, check door rubber gaskets, and switch store lighting to LEDs.\n" +
            "2. **Carry Bags & Packaging:** Charge nominally or encourage reusable cloth bags; buy wholesale thermal rolls & bags quarterly instead of small batches.\n" +
            "3. **Shrinkage & Pilferage Control:** Do weekly spot-checks on high-value small items (dry fruits, chocolates, cosmetics, razor blades).\n" +
            "4. **Delivery Fuel & Routing:** Batch nearby WhatsApp/phone deliveries into 2–3 fixed time slots instead of single-item trips.\n" +
            "5. **Avoid Emergency Local Purchases:** Buying missing stock last-minute from retail/semi-wholesale costs 4%–8% more than planned POs.\n" +
            "6. **Review Subscriptions & Misc Cash:** Log every petty cash expense in `/expenses` — unrecorded ₹100/day leaks ₹3,000/month.");

        return new ToolExecutionResult("CONSULT_EXPENSES", false, md.toString(), List.of(),
            List.of(new NavAction("Open Expenses", "/expenses", "Review expense categories")), List.of(),
            List.of("What are our highest expenses?", "How should I manage cash flow?"));
    }

    private ToolExecutionResult adviseOnCashFlowAndSavings(boolean mgmt) {
        StringBuilder md = new StringBuilder("### Cash Flow & Daily Revenue Savings Guide\n\n");
        if (mgmt) {
            LocalDate today = clock.today();
            FinanceService.Summary sum = financeService.summary(today.withDayOfMonth(1), today);
            md.append(String.format("**Your current store ledger position:**\n" +
                "- **Cash Balance:** %s | **UPI Balance:** %s\n" +
                "- **Supplier Payables Owed:** %s\n\n",
                BusinessInsightService.inr(sum.cashBalance()), BusinessInsightService.inr(sum.upiBalance()),
                BusinessInsightService.inr(sum.supplierOutstanding())));
        }
        md.append("**1. How Much Should You Save from Daily Revenue?**\n" +
            "- Never spend your entire daily cash drawer — **75%–85% of daily revenue belongs to inventory replacement and rent/staff**.\n" +
            "- **Practical Daily Rule (The 3-Jar Method):**\n" +
            "  - **Stock Replenishment (~75%–80%):** Reinvest into fast-moving inventory and supplier payments.\n" +
            "  - **Fixed Bills Reserve (~10%–15%):** Set aside daily for rent, electricity, and monthly salaries.\n" +
            "  - **Net Owner Profit / Emergency Buffer (~5%–10%):** Transfer this to a separate savings account at the end of each week.\n\n" +
            "**2. Cash Flow Best Practices:**\n" +
            "- Keep **Inventory Days on Hand** lean (10–15 days for packaged FMCG, 1–3 days for dairy/produce) so cash isn't trapped on shelves.\n" +
            "- Negotiate **7–14 days credit** with distributors while collecting **100% Cash/UPI at POS** — that keeps your cash flow positive.\n" +
            "- Close your **Cash Shift** daily in `/shifts` to catch cash leakage immediately.");

        return new ToolExecutionResult("CONSULT_CASHFLOW", false, md.toString(), List.of(),
            List.of(new NavAction("Open Shifts", "/shifts", "Manage daily cash drawer")), List.of(),
            List.of("Who owes me money?", "What is my total profit?"));
    }

    private ToolExecutionResult adviseAvoidOverstocking(boolean mgmt) {
        StringBuilder md = new StringBuilder("### How to Avoid Overstocking in Your Grocery Store\n\n");
        if (mgmt) {
            List<Map<String, Object>> slow = financeService.slowMoving(30);
            if (!slow.isEmpty()) {
                BigDecimal tied = slow.stream().map(r -> BusinessInsightService.money(r.get("value"))).reduce(BigDecimal.ZERO, BigDecimal::add);
                md.append(String.format("**Store Check:** You currently have **%d products** that haven't sold in 30 days, tying up **%s** of cash.\n\n",
                    slow.size(), BusinessInsightService.inr(tied)));
            }
        }
        md.append("**5 Practical Inventory Controls:**\n" +
            "1. **Use Smart Reorder (`/reorder`):** Order based on actual 30-day sales velocity + supplier lead time rather than guesswork.\n" +
            "2. **Never Buy Slow Movers for a Scheme:** A \"Buy 10 Get 1 Free\" distributor deal on a slow item traps your cash for 4 months.\n" +
            "3. **ABC Categorization:** Keep 2 weeks of stock for **A-items** (top 20% fast movers), 1 week for **B-items**, and order **C-items** only on demand.\n" +
            "4. **Set Accurate Min Stock Levels:** Review `min_stock_level` quarterly so seasonal dips don't trigger excess POs.\n" +
            "5. **Check Stock Coverage Days in Sales Analytics (`/analytics`):** If an item has >45 days of stock coverage, pause reordering it.");

        return new ToolExecutionResult("CONSULT_OVERSTOCK", false, md.toString(), List.of(),
            List.of(new NavAction("Open Smart Reorder", "/reorder", "View velocity-based reorder quantities")), List.of(),
            List.of("Which products have not sold in 30 days?", "How should I manage products that are close to expiry?"));
    }

    private ToolExecutionResult adviseExpiryAndWastage(boolean mgmt) {
        LocalDate today = clock.today();
        Integer exp7 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM products WHERE is_active = true AND current_stock > 0 AND expiry_date IS NOT NULL AND expiry_date <= :lim",
            new MapSqlParameterSource("lim", Date.valueOf(today.plusDays(7))), Integer.class);

        StringBuilder md = new StringBuilder("### Managing Near-Expiry Products & Reducing Food Wastage\n\n");
        if (exp7 != null && exp7 > 0) {
            md.append(String.format("**Immediate Store Alert:** You have **%d active product(s)** expiring within 7 days (or already expired). Check `/inventory` today.\n\n", exp7));
        }
        md.append("**Step-by-Step Playbook:**\n" +
            "1. **Follow FEFO (First Expired, First Out):** Always place newly received stock *behind* older expiry batches on the shelf.\n" +
            "2. **30-15-5 Day Markdown Rule:**\n" +
            "   - **30 days left:** Move to front-facing eye-level shelves.\n" +
            "   - **15 days left:** Bundle with a fast-moving item (`\"Buy Bread + Jam, save ₹20\"`).\n" +
            "   - **5 days left:** Put in a **\"Today's Saver Basket (25%–40% Off)\"** right near the POS counter.\n" +
            "3. **Negotiate Expiry Replacement Clauses:** Many FMCG/dairy distributors take back unsold damaged/expired stock or issue credit notes if reported 7 days in advance.\n" +
            "4. **Smaller, More Frequent Deliveries:** For milk, curd, bread, and fresh vegetables, order daily or every 2 days instead of weekly.\n" +
            "5. **Track Write-Offs:** Always log damaged/expired items in `/inventory` so you know which categories waste the most money.");

        return new ToolExecutionResult("CONSULT_EXPIRY", false, md.toString(), List.of(),
            List.of(new NavAction("Open Inventory", "/inventory", "Inspect expiring items")), List.of(),
            List.of("Which products are expiring?", "How can I avoid overstocking?"));
    }

    // ---------------------------------------------------------------------------------------------
    // Pure General Retail Consulting (Indian Grocery Domain Expertise)
    // ---------------------------------------------------------------------------------------------

    private ToolExecutionResult adviseSalesGrowth(boolean mgmt) {
        StringBuilder md = new StringBuilder("### How to Increase Monthly Sales by 20% (Action Plan)\n\n");
        if (mgmt) {
            Period mtd = PeriodResolver.thisMonth(clock.today());
            Snapshot s = insight.snapshot(mtd.from(), mtd.to(), null);
            if (s.bills() > 0) {
                BigDecimal plus20 = s.sales().multiply(new BigDecimal("1.20")).setScale(0, RoundingMode.CEILING);
                md.append(String.format("**Your current month-to-date sales:** %s (%d bills, avg %s/bill). A 20%% lift means targeting **%s**.\n\n",
                    BusinessInsightService.inr(s.sales()), s.bills(), BusinessInsightService.inr(s.avgBill()), BusinessInsightService.inr(plus20)));
            }
        }
        md.append("**The 3-Lever Growth Formula (7% more customers + 7% higher bill + 5% more repeat visits = +20% sales):**\n" +
            "1. **Launch WhatsApp Ordering for Your Neighbourhood:** Create a WhatsApp Business broadcast list of 200+ nearby families. Send a weekly *\"Weekend Fresh & Combo Deals\"* photo every Friday morning and promise 30–45 minute home delivery.\n" +
            "2. **Use \"Traffic Magnet\" Pricing on 5 Daily Staples:** Price milk, eggs, onions, or sugar 2%–3% sharper than competitors so families make your store their daily habit — and earn full margin on the rest of their basket.\n" +
            "3. **Create Monthly & Weekly Combo Packs:** Bundle breakfast combos (Bread + Butter + Eggs/Jam) or monthly kirana kits to lift average bill value.\n" +
            "4. **Evening Rush Readiness (5 PM – 9 PM):** Ensure top 50 SKUs are fully restocked by 4:30 PM and POS billing is fast.\n" +
            "5. **Capture Customer Names at POS:** Record customer names on bills so you can identify and reward your top 20 repeat buyers.");

        return new ToolExecutionResult("CONSULT_GROWTH", false, md.toString(), List.of(),
            mgmt ? List.of(new NavAction("Open Sales Analytics", "/analytics", "Track sales growth & peak hours")) : List.of(),
            List.of(),
            List.of("How can I compete with supermarkets and online grocery apps?", "Suggest an interesting customer loyalty program.", "How can I increase average bill value?"));
    }

    private ToolExecutionResult adviseMarketingAndRetention(boolean mgmt) {
        return new ToolExecutionResult("CONSULT_MARKETING", false,
            "### 5 Creative Promotional Ideas & Ways to Increase Repeat Visits\n\n" +
            "1. **\"Wednesday / Weekend Saver\" Corner:** Pick 3 everyday items (e.g., 1kg Dal, 500ml Oil, or Biscuits) at a special 1-day price and announce it on a chalkboard outside your shop + WhatsApp status.\n" +
            "2. **Monthly Ration Stamp Card:** Give a stamp for every ₹500 purchase. On the 6th stamp, offer a free ₹100 grocery voucher or a 1kg pack of rice/sugar.\n" +
            "3. **Free Tasting / Sample Counter:** On Saturdays, sample a new namkeen, tea, or pickle brand near the billing counter — tasting drives immediate impulse sales.\n" +
            "4. **\"Morning Fresh\" Subscription:** Offer monthly prepaid morning delivery of milk + bread + newspaper/eggs to nearby apartments — once you own their morning habit, they order all monthly groceries from you.\n" +
            "5. **Referral Neighbor Bonus:** *\"Bring a neighbor who spends ₹500+ and both of you get ₹50 off.\"* Word-of-mouth is the cheapest marketing in India.",
            List.of(), List.of(), List.of(),
            List.of("Suggest an interesting customer loyalty program.", "What offers can I introduce during festival seasons?", "How can I compete with supermarkets and online grocery apps?"));
    }

    private ToolExecutionResult adviseLoyaltyProgram(boolean mgmt) {
        return new ToolExecutionResult("CONSULT_LOYALTY", false,
            "### Simple, High-ROI Customer Loyalty Program for an Indian Grocery Store\n\n" +
            "Complex point apps often fail in neighbourhood stores. Instead, run a **\"SmartKirana VIP Club\"** that takes 10 seconds at checkout:\n\n" +
            "- **How it works:** Ask cashiers to enter the customer's name/phone at POS checkout. Give **1%–2% cashback credit** (e.g., ₹20 off on next visit for every ₹1,000 spent in a month) OR a **Monthly Milestone Gift** (spend ₹5,000 in a month → get a free 1L cooking oil / 500g dry fruit pack).\n" +
            "- **Why Milestone Gifts beat flat discounts:** A ₹150 gift on ₹5,000 spend costs you only ~2%–3% (at your purchase cost!), feels much more generous than a 2% discount, and locks in the family's entire monthly grocery budget.\n" +
            "- **Birthday / Anniversary Touch:** Send a warm WhatsApp message with a ₹100 festival/birthday voucher.",
            List.of(), List.of(new NavAction("Open POS Billing", "/pos", "Record customer names at checkout")), List.of(),
            List.of("Who are our top customers?", "How can I increase average bill value?"));
    }

    private ToolExecutionResult adviseCompeteWithApps() {
        return new ToolExecutionResult("CONSULT_COMPETE", false,
            "### How to Compete with Supermarkets & 10-Minute Delivery Apps\n\n" +
            "Quick-commerce apps and big supermarkets have deep pockets, **but local grocery stores have 4 unfair advantages they can never match**:\n\n" +
            "1. **Zero Delivery & Platform Fees:** Remind customers via a small counter poster: *\"No ₹30 handling fee, no surge pricing, same-day local delivery!\"*\n" +
            "2. **Personal Trust & Loose/Custom Quantities:** Apps can't sell 100g of fresh coriander, custom-weighted loose dal, or let customers inspect produce quality personally. Highlight fresh, hand-picked quality.\n" +
            "3. **WhatsApp + Phone Speed:** Most local families prefer sending a 10-second voice note or list photo on WhatsApp over scrolling an app. Deliver within 30 minutes for nearby streets.\n" +
            "4. **Flexible Monthly Khata for Verified Regulars:** Offering trusted regulars a clean weekly/monthly settlement cycle creates loyalty apps can't break.",
            List.of(), List.of(), List.of(),
            List.of("How can I increase my monthly sales by 20%?", "Suggest an interesting customer loyalty program."));
    }

    private ToolExecutionResult adviseFestivalOffers() {
        return new ToolExecutionResult("CONSULT_FESTIVAL", false,
            "### High-Converting Festival Season Offers (Diwali, Holi, Eid, Pongal, Navratri)\n\n" +
            "1. **Festive Dry-Fruit & Mithai Gift Hampers:** Pre-pack ₹299, ₹499, and ₹999 gift boxes using high-margin dry fruits, chocolates, and juices — these earn **25%–35% margin**.\n" +
            "2. **\"Festive Cleaning & Kitchen Prep\" Combo:** Bundle floor cleaner + dishwash + scrubber + kitchen towels at 10% off 2 weeks before the festival.\n" +
            "3. **Bulk Sweet-Making Kits:** Bundle Ghee + Besan/Maida + Sugar + Cardamom + Cashews for home cooking.\n" +
            "4. **Lucky Draw on ₹1,500+ Bills:** Every ₹1,500+ bill enters a festive raffle (prizes: mixer-grinder, dinner set, or ₹500 grocery voucher) — hugely boosts average bill size.\n" +
            "5. **Pre-Book Corporate / Society Orders:** Reach out to nearby offices and housing societies 3 weeks early for bulk sweet/dry-fruit boxes.",
            List.of(), List.of(), List.of(),
            List.of("How can I increase average bill value?", "Give me five creative promotional ideas for my grocery store."));
    }

    private ToolExecutionResult adviseSummerStocking() {
        return new ToolExecutionResult("CONSULT_SUMMER", false,
            "### What Products to Stock More During Summer\n\n" +
            "Summer demand shifts sharply toward hydration, cooling dairy, and quick snacks. Increase stock & visibility for:\n\n" +
            "1. **Beverages & Hydration (High Margin):** Cold drinks, bottled water (500ml & 1L), mango/guava juices, buttermilk (chaas), lassi, glucose powder, ORS/electrolytes, and rose/khus syrups (Rooh Afza).\n" +
            "2. **Cooling Dairy & Frozen:** Curd (dahi), paneer, ice creams, kulfi, and cold coffee packs (ensure freezer capacity & backup power).\n" +
            "3. **Seasonal Fruits & Produce:** Watermelon, muskmelon, mangoes, lemons, cucumber, and mint.\n" +
            "4. **Summer Personal Care:** Talcum powder, prickly-heat powder, sunscreen, deodorants, and aloe-vera soaps.\n" +
            "5. **Tip:** Reduce stock of heavy winter items (jaggery, sesame/til snacks, heavy body lotions) 3–4 weeks before peak summer so cash isn't stuck.",
            List.of(), List.of(new NavAction("Open Smart Reorder", "/reorder", "Adjust reorder quantities")), List.of(),
            List.of("Which products need restocking?", "How can I avoid overstocking?"));
    }

    private ToolExecutionResult adviseCommonMistakes() {
        return new ToolExecutionResult("CONSULT_MISTAKES", false,
            "### 7 Common Mistakes Grocery Store Owners Should Avoid\n\n" +
            "1. **Mixing Personal & Shop Cash:** Always separate owner drawings from daily cash register floats using `/shifts` and `/expenses`.\n" +
            "2. **Chasing Revenue Instead of Profit:** Selling only high-volume, low-margin oil/sugar without cross-selling high-margin items leaves little net profit.\n" +
            "3. **Over-Buying on Distributor Schemes:** Free goods on slow-moving products lock up cash and cause expiry write-offs.\n" +
            "4. **Ignoring Dead Stock:** Products sitting unsold for 60+ days silently lose value — discount and clear them early.\n" +
            "5. **No Expiry Rotation (FEFO):** Putting fresh deliveries in front of old stock guarantees spoilage.\n" +
            "6. **Not Tracking Petty Cash Expenses:** Unrecorded ₹100–₹200 daily expenses distort your true P&L.\n" +
            "7. **Running Out of Daily Essentials:** If a customer walks in for milk/bread and leaves empty-handed twice, they switch stores.",
            List.of(), List.of(), List.of(),
            List.of("Do you have any suggestions?", "What is the difference between revenue, gross profit, and net profit?"));
    }

    private ToolExecutionResult explainFinancialTerms(boolean mgmt) {
        StringBuilder md = new StringBuilder("### Revenue vs. Gross Profit vs. Net Profit (In Simple Terms)\n\n" +
            "Imagine you sell a jar of ghee for **₹500** that you bought from your supplier for **₹420**, and your daily shop rent/electricity/salary share is **₹30**:\n\n" +
            "1. **Revenue (Total Sales) = ₹500**\n" +
            "   The total money collected from customers (after discounts/refunds). *High revenue alone doesn't guarantee you're making money.*\n" +
            "2. **Gross Profit = ₹80** (`₹500 Revenue - ₹420 Cost of Goods Sold`)\n" +
            "   What's left after paying for the products you sold. Here your **Gross Margin is 16%** (`80 / 500`).\n" +
            "3. **Net Profit (Operating Profit) = ₹50** (`₹80 Gross Profit - ₹30 Operating Expenses`)\n" +
            "   The **actual money you keep** after paying rent, electricity, staff salaries, transport, and other store expenses.\n\n" +
            "**Key Takeaway:** To grow Net Profit, you either **sell more**, **improve Gross Margin** (sell higher-margin items / buy smarter), or **cut Operating Expenses**.");

        if (mgmt) {
            Period mtd = PeriodResolver.thisMonth(clock.today());
            Snapshot s = insight.snapshot(mtd.from(), mtd.to(), null);
            BigDecimal exp = insight.expensesTotal(mtd.from(), mtd.to());
            if (s.bills() > 0) {
                md.append(String.format("\n\n**In your store %s:** Revenue is **%s**, Gross Profit is **%s** (%s%% margin), Expenses are **%s**, leaving **%s Net Profit**.",
                    mtd.label(), BusinessInsightService.inr(s.lineRevenue()), BusinessInsightService.inr(s.grossProfit()),
                    s.marginPct().toPlainString(), BusinessInsightService.inr(exp), BusinessInsightService.inr(s.grossProfit().subtract(exp))));
            }
        }
        return new ToolExecutionResult("CONSULT_FINANCE_CONCEPTS", false, md.toString(), List.of(),
            mgmt ? List.of(new NavAction("Open Sales Analytics", "/analytics", "View your store's live revenue & profit")) : List.of(),
            List.of(),
            List.of("Am I making enough profit from my current sales?", "How can I improve my profit margin?"));
    }

    private static String roleOf(User u) {
        return u.getRole() != null ? u.getRole().getName() : "STAFF";
    }
}
