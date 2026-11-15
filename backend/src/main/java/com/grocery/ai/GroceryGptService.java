package com.grocery.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grocery.ai.AiDtos.*;
import com.grocery.ai.GroceryBusinessToolService.ToolExecutionResult;
import com.grocery.ai.GroceryKnowledgeBase.KnowledgeArticle;
import com.grocery.ai.PeriodResolver.Period;
import com.grocery.common.CurrentUser;
import com.grocery.common.Exceptions.NotFoundException;
import com.grocery.common.StoreClock;
import com.grocery.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Orchestrates GroceryGPT:
 * - Natural human-like small talk without unnecessary DB calls
 * - Context-aware multi-turn conversation state (persisting active period & topic in ai_conversations.last_context)
 * - Plain-language business explanations & evidence-backed recommendations via BusinessInsightService
 * - Live PostgreSQL operational tools via GroceryBusinessToolService & RAG via GroceryKnowledgeBase
 * - LLM provider synthesis (Claude / OpenAI / Gemini / Grounded Local Engine)
 */
@Service
public class GroceryGptService {

    public static final String WELCOME_MESSAGE =
        "Hey! I'm GroceryGPT, your digital business partner. Ask me about today's sales, profits, low stock, expenses, or what to focus on next — I'll check your live store records and explain what's going on.";

    private final CurrentUser currentUser;
    private final StoreClock clock;
    private final AiConversationRepository conversationRepo;
    private final AiMessageRepository messageRepo;
    private final GroceryKnowledgeBase knowledgeBase;
    private final GroceryBusinessToolService toolService;
    private final BusinessInsightService insightService;
    private final GroceryConsultantService consultantService;
    private final AiProviderClient aiProviderClient;
    private final ObjectMapper objectMapper;

    public GroceryGptService(
        CurrentUser currentUser,
        StoreClock clock,
        AiConversationRepository conversationRepo,
        AiMessageRepository messageRepo,
        GroceryKnowledgeBase knowledgeBase,
        GroceryBusinessToolService toolService,
        BusinessInsightService insightService,
        GroceryConsultantService consultantService,
        AiProviderClient aiProviderClient,
        ObjectMapper objectMapper
    ) {
        this.currentUser = currentUser;
        this.clock = clock;
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
        this.knowledgeBase = knowledgeBase;
        this.toolService = toolService;
        this.insightService = insightService;
        this.consultantService = consultantService;
        this.aiProviderClient = aiProviderClient;
        this.objectMapper = objectMapper;
    }

    private String roleOf(User u) {
        return u.getRole() != null ? u.getRole().getName() : "STAFF";
    }

    private String firstNameOf(User u) {
        String full = u.getFullName() == null ? "" : u.getFullName().trim();
        if (full.isEmpty()) return u.getUsername();
        int sp = full.indexOf(' ');
        return sp > 0 ? full.substring(0, sp) : full;
    }

    public SuggestionsResponse getSuggestions() {
        User u = currentUser.require();
        String role = roleOf(u);
        List<PromptCategoryDto> categories = new ArrayList<>();
        List<String> quick = new ArrayList<>();

        if ("OWNER".equals(role) || "MANAGER".equals(role)) {
            quick.addAll(List.of(
                "How are my sales today?",
                "How can I improve my profit?",
                "Which products need restocking?",
                "How can I increase my monthly sales by 20%?",
                "Do you have any suggestions?"
            ));
            categories.add(new PromptCategoryDto("Sales & Profit", List.of(
                "How are my sales today?",
                "What about yesterday?",
                "What is my total profit?",
                "How much profit did we make this month?",
                "Which product sells the most?",
                "Which product sells the least?",
                "What is the best-performing category?",
                "Which products have declining sales?"
            )));
            categories.add(new PromptCategoryDto("Business Growth & Strategy", List.of(
                "How can I increase my monthly sales by 20%?",
                "How can I improve my profit?",
                "How can I compete with supermarkets and online grocery apps?",
                "Suggest an interesting customer loyalty program.",
                "What offers can I introduce during festival seasons?",
                "Give me five creative promotional ideas for my grocery store."
            )));
            categories.add(new PromptCategoryDto("Inventory & Financial Planning", List.of(
                "Which products need restocking?",
                "Which products are expiring?",
                "How can I avoid overstocking?",
                "What products should I stock more during summer?",
                "How should I manage products that are close to expiry?",
                "How much should I save from my daily revenue?",
                "What is the difference between revenue, gross profit, and net profit?"
            )));
            categories.add(new PromptCategoryDto("Customers & Expenses", List.of(
                "Who are our top customers?",
                "Who owes me money?",
                "What are our highest expenses?",
                "How can I reduce my monthly expenses?",
                "What is my current cash balance?",
                "Do you have any suggestions?"
            )));
            if ("OWNER".equals(role)) {
                categories.add(new PromptCategoryDto("Employees & Payroll", List.of(
                    "How many employees are present today?",
                    "Who is absent today?",
                    "Who has the highest attendance?",
                    "What is the total salary expenditure?",
                    "Which employee salaries are pending?"
                )));
            } else {
                categories.add(new PromptCategoryDto("Employee Attendance", List.of(
                    "How many employees are present today?",
                    "Who is absent today?",
                    "Who has the highest attendance?",
                    "How many overtime hours were recorded?"
                )));
            }
        } else {
            // STAFF
            quick.addAll(List.of(
                "How are my sales today?",
                "Which products need restocking?",
                "Which products are expiring?",
                "What is my attendance percentage?",
                "Do you have any suggestions?"
            ));
            categories.add(new PromptCategoryDto("My Daily Work", List.of(
                "How are my sales today?",
                "What is my attendance percentage?",
                "Which products need restocking?",
                "Which products are expiring?",
                "Do you have any suggestions?"
            )));
        }

        categories.add(new PromptCategoryDto("Application Guidance", List.of(
            "How do I add a product?",
            "How do I create a purchase order?",
            "How do I record an expense?",
            "How do I open or close a cash shift?",
            "How do I add an employee?",
            "How do I mark attendance?",
            "How do I approve leave?",
            "How do I generate payroll?",
            "How do I view sales analytics?"
        )));

        return new SuggestionsResponse(
            WELCOME_MESSAGE,
            role,
            aiProviderClient.getActiveProviderLabel(),
            quick,
            categories
        );
    }

    @Transactional
    public ChatResponse chat(ChatRequest req) {
        User user = currentUser.require();
        String role = roleOf(user);
        String firstName = firstNameOf(user);
        String msg = req.message().trim();

        AiConversation conv;
        if (req.conversationId() != null) {
            conv = conversationRepo.findByIdAndUserId(req.conversationId(), user.getId())
                .orElseThrow(() -> new NotFoundException("Conversation not found"));
        } else {
            conv = new AiConversation();
            conv.userId = user.getId();
            conv.title = buildTitle(msg);
            conv = conversationRepo.save(conv);
        }

        List<AiMessage> recentDesc = messageRepo.findTop10ByConversationIdOrderByCreatedAtDesc(conv.id);
        List<AiMessage> recentHistory = new ArrayList<>(recentDesc);
        Collections.reverse(recentHistory);

        // Save user message
        AiMessage userMsg = new AiMessage();
        userMsg.conversationId = conv.id;
        userMsg.role = "USER";
        userMsg.content = msg;
        messageRepo.save(userMsg);

        // 1. Check natural small talk first (no unnecessary DB calls!)
        String normalized = SmallTalk.normalize(msg);
        if (!SmallTalk.mentionsBusiness(normalized)) {
            SmallTalk.Reply small = SmallTalk.reply(msg, firstName, role);
            if (small != null) {
                AiMessage assistantMsg = new AiMessage();
                assistantMsg.conversationId = conv.id;
                assistantMsg.role = "ASSISTANT";
                assistantMsg.content = small.text();
                assistantMsg.intent = "SMALL_TALK";
                assistantMsg.toolsInvoked = "";
                assistantMsg.structuredData = serializeStructured(List.of(), List.of(), List.of(), small.suggestions());
                assistantMsg = messageRepo.save(assistantMsg);

                conv.updatedAt = Instant.now();
                conversationRepo.save(conv);

                return new ChatResponse(
                    conv.id, conv.title, assistantMsg.id, small.text(), "SMALL_TALK",
                    "CONVERSATIONAL", List.of(), List.of(), List.of(), List.of(),
                    small.suggestions(), assistantMsg.createdAt
                );
            }
        }

        // 2. Load previous conversation context (period, previous period, last topic)
        LocalDate today = clock.today();
        Map<String, String> ctx = readContext(conv.lastContext);
        Period mentionedPeriod = PeriodResolver.parse(msg, today);
        Period prevActivePeriod = restorePeriod(ctx, "cur", today);
        Period prevComparedPeriod = restorePeriod(ctx, "prev", today);

        // 3. Classify intent with context awareness
        String intent = classifyIntent(msg, conv.lastIntent, mentionedPeriod);

        List<String> toolsInvoked = new ArrayList<>();
        List<TablePayload> tables = new ArrayList<>();
        List<NavAction> navLinks = new ArrayList<>();
        List<DownloadAction> downloads = new ArrayList<>();
        List<String> followUps = new ArrayList<>();
        StringBuilder groundedMarkdown = new StringBuilder();

        Period nextActivePeriod = prevActivePeriod;
        Period nextComparedPeriod = prevComparedPeriod;

        // 4. Execute appropriate business insight, live DB tool, or RAG guidance
        switch (intent) {
            case "SALES_EXPLAIN" -> {
                Period a = prevActivePeriod != null ? prevActivePeriod : PeriodResolver.today(today);
                Period b = (prevComparedPeriod != null && !prevComparedPeriod.key().equals(a.key()))
                    ? prevComparedPeriod
                    : PeriodResolver.of(a.prevFrom(), a.prevTo(), a.prevLabel(), today);
                ToolExecutionResult res = insightService.explainSalesChange(
                    user, a.from(), a.to(), a.label(), b.from(), b.to(), b.label()
                );
                appendTool(res, toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
                nextActivePeriod = a;
                nextComparedPeriod = b;
            }
            case "SALES_PERIOD" -> {
                Period target = mentionedPeriod != null ? mentionedPeriod : PeriodResolver.today(today);
                ToolExecutionResult res = insightService.salesForPeriod(user, target);
                appendTool(res, toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
                if (prevActivePeriod != null && !prevActivePeriod.key().equals(target.key())) {
                    nextComparedPeriod = prevActivePeriod;
                } else {
                    nextComparedPeriod = PeriodResolver.of(target.prevFrom(), target.prevTo(), target.prevLabel(), today);
                }
                nextActivePeriod = target;
            }
            case "PROFIT_PERIOD" -> {
                Period target = mentionedPeriod != null ? mentionedPeriod : PeriodResolver.thisMonth(today);
                ToolExecutionResult res = insightService.profitForPeriod(user, target);
                appendTool(res, toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
                nextActivePeriod = target;
                nextComparedPeriod = PeriodResolver.of(target.prevFrom(), target.prevTo(), target.prevLabel(), today);
            }
            case "EXPENSES_INSIGHT" -> {
                Period target = mentionedPeriod != null ? mentionedPeriod : PeriodResolver.thisMonth(today);
                ToolExecutionResult res = insightService.expensesInsight(user, target);
                appendTool(res, toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
                nextActivePeriod = target;
                nextComparedPeriod = PeriodResolver.of(target.prevFrom(), target.prevTo(), target.prevLabel(), today);
            }
            case "TOP_CUSTOMERS" -> {
                Period target = mentionedPeriod != null ? mentionedPeriod : PeriodResolver.lastNDays(30, today);
                ToolExecutionResult res = insightService.topCustomers(user, target);
                appendTool(res, toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
                nextActivePeriod = target;
            }
            case "RECEIVABLES" -> {
                appendTool(insightService.receivables(user), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            }
            case "RECOMMENDATIONS" -> {
                appendTool(insightService.recommendations(user), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            }
            case "BUSINESS_CONSULTING" -> {
                appendTool(consultantService.advise(user, msg), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            }
            case "SALES_BEST_PRODUCT" -> appendTool(toolService.getBestAndLeastSellingProducts(user, false, "LAST_30_DAYS"), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "SALES_LEAST_PRODUCT" -> appendTool(toolService.getBestAndLeastSellingProducts(user, true, "LAST_30_DAYS"), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "SALES_CATEGORY" -> appendTool(toolService.getCategoryPerformance(user), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "SALES_MONTH_COMPARE" -> appendTool(toolService.getMonthComparisonAndProfit(user), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "SALES_DECLINING" -> appendTool(toolService.getDecliningProducts(user), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "INV_RESTOCK" -> appendTool(toolService.getRestockingNeeds(user), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "INV_EXPIRING" -> appendTool(toolService.getExpiringProducts(user), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "INV_VALUATION" -> appendTool(toolService.getInventoryValuationAndDeadStock(user, false), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "INV_SLOW_MOVING" -> appendTool(toolService.getInventoryValuationAndDeadStock(user, true), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "INV_SUPPLIER_PRICE" -> appendTool(toolService.getSupplierLowestPrices(user), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "HR_ATTENDANCE" -> appendTool(toolService.getAttendanceOverview(user, msg), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "HR_PAYROLL" -> appendTool(toolService.getPayrollAndSalarySummary(user), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "ACCOUNTING_FINANCE" -> appendTool(toolService.getAccountingAndFinancialReport(user, msg), toolsInvoked, tables, navLinks, downloads, followUps, groundedMarkdown);
            case "APP_GUIDANCE" -> {
                toolsInvoked.add("KNOWLEDGE_BASE_RAG");
                List<KnowledgeArticle> matches = knowledgeBase.search(msg, role, 2);
                if (!matches.isEmpty()) {
                    for (KnowledgeArticle a : matches) {
                        groundedMarkdown.append(knowledgeBase.formatArticleResponse(a, role)).append("\n\n");
                        for (NavAction n : a.navActions()) {
                            if (navLinks.stream().noneMatch(existing -> existing.path().equals(n.path()))) {
                                navLinks.add(n);
                            }
                        }
                    }
                } else {
                    groundedMarkdown.append(knowledgeBase.getFullSystemOverview());
                }
                followUps.addAll(List.of(
                    "How are my sales today?",
                    "Which products need restocking?",
                    "Do you have any suggestions?"
                ));
            }
            default -> {
                List<KnowledgeArticle> matches = knowledgeBase.search(msg, role, 1);
                if (!matches.isEmpty()) {
                    toolsInvoked.add("KNOWLEDGE_BASE_RAG");
                    KnowledgeArticle a = matches.get(0);
                    groundedMarkdown.append(knowledgeBase.formatArticleResponse(a, role));
                    navLinks.addAll(a.navActions());
                } else {
                    toolsInvoked.add("SYSTEM_OVERVIEW");
                    groundedMarkdown.append("I'm not quite sure what specific figure you're looking for, so here's a quick look at what I can check for you:\n\n");
                    groundedMarkdown.append(knowledgeBase.getFullSystemOverview());
                }
                followUps.addAll(List.of(
                    "How are my sales today?",
                    "What is my total profit?",
                    "Which products need restocking?",
                    "Do you have any suggestions?"
                ));
            }
        }

        // Prefix warm greeting if user opened with "Hi, ..."
        String prefix = SmallTalk.greetingPrefix(msg, firstName);
        String groundedText = prefix + groundedMarkdown.toString().trim();

        // Synthesize final answer via configured LLM provider (or grounded local engine)
        AiProviderClient.SynthesisResult synth = aiProviderClient.synthesize(
            role,
            user.getFullName(),
            msg,
            groundedText,
            recentHistory
        );

        // Persist assistant response
        AiMessage assistantMsg = new AiMessage();
        assistantMsg.conversationId = conv.id;
        assistantMsg.role = "ASSISTANT";
        assistantMsg.content = synth.text();
        assistantMsg.intent = intent;
        assistantMsg.toolsInvoked = String.join(",", toolsInvoked);
        assistantMsg.structuredData = serializeStructured(tables, navLinks, downloads, followUps);
        assistantMsg = messageRepo.save(assistantMsg);

        conv.lastIntent = intent;
        conv.lastContext = writeContext(nextActivePeriod, nextComparedPeriod);
        conv.updatedAt = Instant.now();
        conversationRepo.save(conv);

        return new ChatResponse(
            conv.id,
            conv.title,
            assistantMsg.id,
            synth.text(),
            intent,
            synth.providerUsed(),
            toolsInvoked,
            tables,
            navLinks,
            downloads,
            followUps,
            assistantMsg.createdAt
        );
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> listConversations() {
        User u = currentUser.require();
        return conversationRepo.findTop30ByUserIdOrderByUpdatedAtDesc(u.getId()).stream()
            .map(c -> new ConversationSummaryDto(c.id, c.title, c.lastIntent, c.createdAt, c.updatedAt))
            .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailDto getConversation(Long id) {
        User u = currentUser.require();
        AiConversation c = conversationRepo.findByIdAndUserId(id, u.getId())
            .orElseThrow(() -> new NotFoundException("Conversation not found"));
        List<MessageDto> msgs = messageRepo.findByConversationIdOrderByCreatedAtAsc(c.id).stream()
            .map(this::toMessageDto)
            .toList();
        return new ConversationDetailDto(c.id, c.title, c.lastIntent, c.createdAt, c.updatedAt, msgs);
    }

    @Transactional
    public void deleteConversation(Long id) {
        User u = currentUser.require();
        AiConversation c = conversationRepo.findByIdAndUserId(id, u.getId())
            .orElseThrow(() -> new NotFoundException("Conversation not found"));
        conversationRepo.delete(c);
    }

    @Transactional
    public void clearAllConversations() {
        User u = currentUser.require();
        conversationRepo.deleteByUserId(u.getId());
    }

    String classifyIntent(String rawMessage, String lastIntent, Period mentionedPeriod) {
        String q = rawMessage.toLowerCase(Locale.ROOT).trim();

        // 1. Business Consulting & General Retail Knowledge (profit improvement, marketing, loyalty, cash flow, summer/festival, wastage)
        if (isBusinessConsultingQuery(q)) {
            return "BUSINESS_CONSULTING";
        }

        // 2. Explicit "How do I..." / step-by-step app guidance queries
        if (q.startsWith("how do i") || q.startsWith("how to") || q.startsWith("how can i") ||
            q.startsWith("where do i") || q.startsWith("steps to") || q.contains("step by step") ||
            q.contains("guide to")) {
            return "APP_GUIDANCE";
        }

        // 2. Smart recommendations / business advice
        if (q.contains("suggest") || q.contains("recommend") || q.contains("advice") ||
            q.contains("improve my business") || q.contains("improve business") || q.contains("improve sales") ||
            q.contains("what should i focus") || q.contains("what should i do") || q.contains("any insights") ||
            q.contains("business health") || q.contains("how can i grow") || q.contains("how to grow")) {
            return "RECOMMENDATIONS";
        }

        // 3. "Why" follow-up questions about sales changes
        if (q.startsWith("why ") || q.contains("why is") || q.contains("why are") || q.contains("why did") ||
            q.contains("what caused") || q.contains("reason for") || q.contains("what drove")) {
            if (q.contains("sale") || q.contains("revenue") || q.contains("higher") || q.contains("lower") ||
                q.contains("up") || q.contains("down") || q.contains("increase") || q.contains("drop") ||
                (lastIntent != null && lastIntent.startsWith("SALES"))) {
                return "SALES_EXPLAIN";
            }
        }

        // 4. Context-aware follow-up queries ("What about yesterday?", "And last month?")
        if (lastIntent != null && isFollowUpQuery(q)) {
            if (q.contains("least") || q.contains("worst") || q.contains("bottom") || q.contains("lowest")) {
                if (lastIntent.startsWith("SALES")) return "SALES_LEAST_PRODUCT";
            }
            if (q.contains("best") || q.contains("most") || q.contains("top")) {
                if (lastIntent.startsWith("SALES")) return "SALES_BEST_PRODUCT";
            }
            if (q.contains("category") || q.contains("categories")) {
                return "SALES_CATEGORY";
            }
            if (q.contains("absent") || q.contains("present") || q.contains("overtime") || q.contains("attendance")) {
                return "HR_ATTENDANCE";
            }
            if (q.contains("salary") || q.contains("salaries") || q.contains("payroll")) {
                return "HR_PAYROLL";
            }
            if (mentionedPeriod != null) {
                if ("PROFIT_PERIOD".equals(lastIntent)) return "PROFIT_PERIOD";
                if ("EXPENSES_INSIGHT".equals(lastIntent)) return "EXPENSES_INSIGHT";
                if ("TOP_CUSTOMERS".equals(lastIntent)) return "TOP_CUSTOMERS";
                if (lastIntent.startsWith("SALES")) return "SALES_PERIOD";
            }
            return lastIntent;
        }

        // 5. Customers & pending money / receivables
        if (q.contains("top customer") || q.contains("best customer") || q.contains("regular customer") ||
            q.contains("who buys the most") || q.contains("loyal customer")) {
            return "TOP_CUSTOMERS";
        }
        if (q.contains("who owes") || q.contains("owe me") || q.contains("pending from customer") ||
            q.contains("customer pending") || q.contains("receivable") || q.contains("udhaar") ||
            q.contains("credit balance") || q.contains("pending payment")) {
            return "RECEIVABLES";
        }

        // 6. Employee, Attendance & Payroll intents
        if (q.contains("salary") || q.contains("salaries") || q.contains("payroll") || q.contains("payslip") ||
            (q.contains("pending") && q.contains("employee"))) {
            return "HR_PAYROLL";
        }
        if (q.contains("present today") || q.contains("absent today") || q.contains("who is absent") ||
            q.contains("who is present") || q.contains("attendance") || q.contains("overtime") ||
            q.contains("late arrival") || q.contains("on leave")) {
            return "HR_ATTENDANCE";
        }

        // 7. Inventory & Supplier intents
        if (q.contains("restock") || q.contains("low stock") || q.contains("out of stock") || q.contains("reorder")) {
            return "INV_RESTOCK";
        }
        if (q.contains("expir")) {
            return "INV_EXPIRING";
        }
        if (q.contains("not sold in 30") || q.contains("haven't sold") || q.contains("slow moving") ||
            q.contains("slow-moving") || q.contains("dead stock")) {
            return "INV_SLOW_MOVING";
        }
        if (q.contains("inventory value") || q.contains("inventory valuation") || q.contains("stock value") ||
            q.contains("worth of stock")) {
            return "INV_VALUATION";
        }
        if (q.contains("supplier") && (q.contains("lowest") || q.contains("cheapest") || q.contains("price") || q.contains("cost"))) {
            return "INV_SUPPLIER_PRICE";
        }

        // 8. Expenses (natural anomaly + category breakdown)
        if (q.contains("highest expense") || q.contains("expenses") || q.contains("spending") ||
            q.contains("what did we spend") || q.contains("expense")) {
            return "EXPENSES_INSIGHT";
        }

        // 9. Accounting balances & reports
        if (q.contains("owe supplier") || q.contains("supplier payable") || q.contains("cash balance") ||
            q.contains("upi balance") || q.contains("financial report") || q.contains("p&l") ||
            q.contains("profit and loss")) {
            return "ACCOUNTING_FINANCE";
        }

        // 10. Profit
        if (q.contains("profit") || q.contains("margin") || q.contains("net income") || q.contains("loss")) {
            return "PROFIT_PERIOD";
        }

        // 11. Product & Category Sales Analytics
        if (q.contains("declining") || q.contains("dropping sales") || q.contains("falling sales")) {
            return "SALES_DECLINING";
        }
        if (q.contains("sells the least") || q.contains("least selling") || q.contains("least-selling") ||
            q.contains("lowest selling") || q.contains("worst selling") || q.contains("bottom product")) {
            return "SALES_LEAST_PRODUCT";
        }
        if (q.contains("sells the most") || q.contains("best selling") || q.contains("best-selling") ||
            q.contains("top selling") || q.contains("top product") || q.contains("most sold")) {
            return "SALES_BEST_PRODUCT";
        }
        if (q.contains("category") || q.contains("categories")) {
            return "SALES_CATEGORY";
        }
        if (q.contains("compare this month") && q.contains("last month")) {
            return "SALES_MONTH_COMPARE";
        }

        // 12. Period sales ("How are our sales today?", "What about yesterday?", "How was last week?", "Today's revenue")
        if (q.contains("sale") || q.contains("sales") || q.contains("revenue") || q.contains("turnover") ||
            mentionedPeriod != null) {
            return "SALES_PERIOD";
        }

        // 13. Fallback to RAG if it matches any module keyword
        if (!knowledgeBase.search(rawMessage, "OWNER", 1).isEmpty()) {
            return "APP_GUIDANCE";
        }

        return "GENERAL_OVERVIEW";
    }

    private boolean isBusinessConsultingQuery(String q) {
        return (q.contains("difference between") && (q.contains("revenue") || q.contains("gross profit") || q.contains("net profit"))) ||
               ((q.contains("improve") || q.contains("increase") || q.contains("boost")) && (q.contains("profit") || q.contains("margin") || q.contains("sale") || q.contains("average bill") || q.contains("ticket size"))) ||
               q.contains("enough profit") ||
               ((q.contains("which product") || q.contains("what product")) && (q.contains("focus") || q.contains("push") || q.contains("sell more") || q.contains("promote"))) ||
               ((q.contains("reduce") || q.contains("cut") || q.contains("lower")) && (q.contains("expense") || q.contains("cost") || q.contains("wastage") || q.contains("waste"))) ||
               q.contains("cash flow") || q.contains("cashflow") ||
               (q.contains("save") && q.contains("daily")) ||
               q.contains("overstock") ||
               (q.contains("summer") && q.contains("stock")) ||
               (q.contains("manage") && q.contains("expir")) || q.contains("close to expiry") || q.contains("near expiry") ||
               q.contains("loyalty") || q.contains("festival") || q.contains("promotional") || q.contains("promotion") ||
               q.contains("compete") || q.contains("supermarket") || q.contains("online grocery") ||
               q.contains("attract") && q.contains("customer") ||
               q.contains("visit") && q.contains("frequently") ||
               q.contains("mistake") && q.contains("avoid") ||
               q.contains("creative") && q.contains("idea");
    }

    private boolean isFollowUpQuery(String q) {
        return q.startsWith("what about") || q.startsWith("how about") || q.startsWith("and ") ||
               q.startsWith("compare that") || q.startsWith("show me the least") ||
               q.startsWith("what is their") || q.startsWith("who else") || q.length() < 28;
    }

    private void appendTool(
        ToolExecutionResult res,
        List<String> toolsInvoked,
        List<TablePayload> tables,
        List<NavAction> navLinks,
        List<DownloadAction> downloads,
        List<String> followUps,
        StringBuilder md
    ) {
        toolsInvoked.add(res.toolName());
        md.append(res.summaryMarkdown()).append("\n");
        tables.addAll(res.tables());
        navLinks.addAll(res.navLinks());
        downloads.addAll(res.downloads());
        followUps.addAll(res.followUpSuggestions());
    }

    private String buildTitle(String msg) {
        String clean = msg.replaceAll("\\s+", " ").trim();
        return clean.length() <= 60 ? clean : clean.substring(0, 57) + "...";
    }

    private Map<String, String> readContext(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Period restorePeriod(Map<String, String> ctx, String prefix, LocalDate today) {
        String fromStr = ctx.get(prefix + "From");
        String toStr = ctx.get(prefix + "To");
        String label = ctx.get(prefix + "Label");
        if (fromStr == null || toStr == null || label == null) return null;
        try {
            return PeriodResolver.of(LocalDate.parse(fromStr), LocalDate.parse(toStr), label, today);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeContext(Period cur, Period prev) {
        try {
            Map<String, String> map = new LinkedHashMap<>();
            if (cur != null) {
                map.put("curFrom", cur.from().toString());
                map.put("curTo", cur.to().toString());
                map.put("curLabel", cur.label());
            }
            if (prev != null) {
                map.put("prevFrom", prev.from().toString());
                map.put("prevTo", prev.to().toString());
                map.put("prevLabel", prev.label());
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String serializeStructured(
        List<TablePayload> tables,
        List<NavAction> navLinks,
        List<DownloadAction> downloads,
        List<String> followUps
    ) {
        try {
            Map<String, Object> map = Map.of(
                "tables", tables,
                "navLinks", navLinks,
                "downloads", downloads,
                "followUpSuggestions", followUps
            );
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private MessageDto toMessageDto(AiMessage m) {
        List<String> tools = (m.toolsInvoked == null || m.toolsInvoked.isBlank())
            ? List.of()
            : Arrays.asList(m.toolsInvoked.split(","));
        List<TablePayload> tables = List.of();
        List<NavAction> navLinks = List.of();
        List<DownloadAction> downloads = List.of();
        List<String> followUps = List.of();

        if (m.structuredData != null && !m.structuredData.isBlank()) {
            try {
                Map<String, Object> raw = objectMapper.readValue(m.structuredData, new TypeReference<>() {});
                if (raw.get("tables") != null) {
                    tables = objectMapper.convertValue(raw.get("tables"), new TypeReference<List<TablePayload>>() {});
                }
                if (raw.get("navLinks") != null) {
                    navLinks = objectMapper.convertValue(raw.get("navLinks"), new TypeReference<List<NavAction>>() {});
                }
                if (raw.get("downloads") != null) {
                    downloads = objectMapper.convertValue(raw.get("downloads"), new TypeReference<List<DownloadAction>>() {});
                }
                if (raw.get("followUpSuggestions") != null) {
                    followUps = objectMapper.convertValue(raw.get("followUpSuggestions"), new TypeReference<List<String>>() {});
                }
            } catch (Exception ignored) {}
        }

        return new MessageDto(
            m.id,
            m.role,
            m.content,
            m.intent,
            tools,
            tables,
            navLinks,
            downloads,
            followUps,
            m.createdAt
        );
    }
}
