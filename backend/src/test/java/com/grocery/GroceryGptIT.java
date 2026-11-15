package com.grocery;

import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroceryGptIT extends AbstractIT {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP = new ParameterizedTypeReference<>() {};

    @Test
    @SuppressWarnings("unchecked")
    void groceryGptHandlesSmallTalkBusinessInsightsRecommendationsFollowUpsAndRbac() {
        String owner = ownerToken();
        String manager = createUser(owner, "MANAGER");
        String staff = createUser(owner, "STAFF");

        // 1. Verify Suggestions & Welcome Message
        var sugRes = call(HttpMethod.GET, "/api/ai/suggestions", owner, null);
        assertThat(sugRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) sugRes.getBody().get("quickSuggestions")).contains(
            "How are my sales today?",
            "How can I improve my profit?",
            "Which products need restocking?",
            "How can I increase my monthly sales by 20%?",
            "Do you have any suggestions?"
        );

        // 2. Natural Small Talk (no DB query required)
        var hiRes = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of("message", "Hi"));
        assertThat(hiRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) hiRes.getBody().get("intent")).isEqualTo("SMALL_TALK");
        assertThat((List<?>) hiRes.getBody().get("toolsInvoked")).isEmpty();
        Long convId = ((Number) hiRes.getBody().get("conversationId")).longValue();

        var howRes = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of("conversationId", convId, "message", "How are you?"));
        assertThat(howRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) howRes.getBody().get("intent")).isEqualTo("SMALL_TALK");

        var capRes = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of("conversationId", convId, "message", "What can you do?"));
        assertThat(capRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) capRes.getBody().get("intent")).isEqualTo("SMALL_TALK");

        var thxRes = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of("conversationId", convId, "message", "Thank you"));
        assertThat(thxRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) thxRes.getBody().get("intent")).isEqualTo("SMALL_TALK");

        // 3. Seed a product & completed sale so live business queries return actual PostgreSQL figures
        var catRes = call(HttpMethod.POST, "/api/categories", owner, Map.of("name", "AI Dairy", "description", "Dairy items"));
        Long catId = ((Number) catRes.getBody().get("id")).longValue();

        var prodRes = call(HttpMethod.POST, "/api/products", owner, Map.of(
            "name", "Amul Gold Milk 1L",
            "categoryId", catId,
            "unit", "LITRE",
            "purchasePrice", "54.00",
            "sellingPrice", "68.00",
            "initialStock", "25",
            "minStockLevel", "10"
        ));
        Long prodId = ((Number) prodRes.getBody().get("id")).longValue();

        call(HttpMethod.POST, "/api/shifts/open", owner, Map.of("openingCash", "500.00", "notes", "AI test shift"));
        call(HttpMethod.POST, "/api/sales", owner, Map.of(
            "customerName", "Ramesh Patel",
            "discountTotal", "0.00",
            "items", List.of(Map.of("productId", prodId, "quantity", "5", "discount", "0.00")),
            "payments", List.of(Map.of("method", "CASH", "amount", "340.00"))
        ));

        // 4. Conversational Business Analysis + Contextual Follow-Ups ("today" -> "yesterday" -> "why?")
        var chatSalesToday = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of(
            "conversationId", convId,
            "message", "How are my sales today?"
        ));
        assertThat(chatSalesToday.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatSalesToday.getBody().get("intent")).isEqualTo("SALES_PERIOD");
        assertThat((String) chatSalesToday.getBody().get("answer")).contains("340");
        assertThat((List<?>) chatSalesToday.getBody().get("tables")).isNotEmpty();

        var chatYesterday = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of(
            "conversationId", convId,
            "message", "What about yesterday?"
        ));
        assertThat(chatYesterday.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatYesterday.getBody().get("intent")).isEqualTo("SALES_PERIOD");

        var chatWhy = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of(
            "conversationId", convId,
            "message", "Why are sales higher?"
        ));
        assertThat(chatWhy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatWhy.getBody().get("intent")).isEqualTo("SALES_EXPLAIN");
        assertThat((String) chatWhy.getBody().get("answer")).contains("Amul Gold Milk 1L");

        // 5. Profit, Top Customers, Receivables, Expenses, and Recommendations
        var chatProfit = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of(
            "conversationId", convId,
            "message", "How much profit did we make this month?"
        ));
        assertThat(chatProfit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatProfit.getBody().get("intent")).isEqualTo("PROFIT_PERIOD");

        var chatCust = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of(
            "conversationId", convId,
            "message", "Who are our top customers?"
        ));
        assertThat(chatCust.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatCust.getBody().get("intent")).isEqualTo("TOP_CUSTOMERS");
        assertThat((String) chatCust.getBody().get("answer")).contains("Ramesh Patel");

        var chatOwe = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of(
            "conversationId", convId,
            "message", "Who owes me money?"
        ));
        assertThat(chatOwe.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatOwe.getBody().get("intent")).isEqualTo("RECEIVABLES");

        var chatExp = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of(
            "conversationId", convId,
            "message", "What are our highest expenses?"
        ));
        assertThat(chatExp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatExp.getBody().get("intent")).isEqualTo("EXPENSES_INSIGHT");

        var chatRec = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of(
            "conversationId", convId,
            "message", "Do you have any suggestions?"
        ));
        assertThat(chatRec.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatRec.getBody().get("intent")).isEqualTo("RECOMMENDATIONS");

        // 5b. Business Consulting & General Knowledge (data + strategy)
        var chatConsultProfit = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of(
            "conversationId", convId,
            "message", "How can I improve my profit?"
        ));
        assertThat(chatConsultProfit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatConsultProfit.getBody().get("intent")).isEqualTo("BUSINESS_CONSULTING");
        assertThat((String) chatConsultProfit.getBody().get("answer")).contains("Profit Diagnosis");

        var chatLoyalty = call(HttpMethod.POST, "/api/ai/chat", owner, Map.of(
            "conversationId", convId,
            "message", "Suggest an interesting customer loyalty program."
        ));
        assertThat(chatLoyalty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatLoyalty.getBody().get("intent")).isEqualTo("BUSINESS_CONSULTING");

        // 6. Ask Application Guidance ("How do I add a product?")
        var chatGuide = call(HttpMethod.POST, "/api/ai/chat", staff, Map.of(
            "message", "How do I add a product?"
        ));
        assertThat(chatGuide.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) chatGuide.getBody().get("intent")).isEqualTo("APP_GUIDANCE");
        assertThat((String) chatGuide.getBody().get("answer")).contains("Step-by-Step Instructions");
        assertThat((List<?>) chatGuide.getBody().get("navLinks")).isNotEmpty();

        // 7. Verify Role-Based Security (RBAC):
        var staffSalaryTry = call(HttpMethod.POST, "/api/ai/chat", staff, Map.of(
            "message", "What is the total salary expenditure?"
        ));
        assertThat(staffSalaryTry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) staffSalaryTry.getBody().get("answer")).contains("Access Restricted");

        var mgrSalaryTry = call(HttpMethod.POST, "/api/ai/chat", manager, Map.of(
            "message", "Which employee salaries are pending?"
        ));
        assertThat(mgrSalaryTry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) mgrSalaryTry.getBody().get("answer")).contains("Access Restricted");

        var staffProfitTry = call(HttpMethod.POST, "/api/ai/chat", staff, Map.of(
            "message", "What is my total profit?"
        ));
        assertThat(staffProfitTry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) staffProfitTry.getBody().get("answer")).contains("Access Restricted");

        // 8. Verify Conversation History Persistence & Deletion
        var convList = rest.exchange("/api/ai/conversations", HttpMethod.GET, new HttpEntity<>(auth(owner)), LIST_MAP);
        assertThat(convList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(convList.getBody()).isNotEmpty();

        var delRes = call(HttpMethod.DELETE, "/api/ai/conversations/" + convId, owner, null);
        assertThat(delRes.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
