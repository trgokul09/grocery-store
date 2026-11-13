package com.grocery;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class CheckoutIT extends AbstractIT {

    private Map<String, Object> cart(Number productId, int qty, String payAmount) {
        return Map.of("items", List.of(Map.of("productId", productId, "quantity", qty)), "payments", List.of(Map.of("method", "CASH", "amount", payAmount)));
    }

    @Test void staffMustOpenShiftThenCheckoutDeductsStockAndLogsMovement() {
        String owner = ownerToken(), staff = createUser(owner, "STAFF");
        Number id = createProduct(owner, "CHK-1", 10, "100.00");     // plain price, no tax

        assertThat(call(HttpMethod.POST, "/api/sales/checkout", staff, cart(id, 1, "100.00")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // no shift
        assertThat(call(HttpMethod.POST, "/api/shifts/open", staff, Map.of("openingCash", 500)).getStatusCode()).isEqualTo(HttpStatus.OK);

        var ok = call(HttpMethod.POST, "/api/sales/checkout", staff, cart(id, 2, "200.00"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(product(owner, id).get("currentStock").toString()).startsWith("8");

        // shift close: expected = 500 + 200
        var close = call(HttpMethod.POST, "/api/shifts/close", staff, Map.of("countedCash", 690));
        assertThat(new java.math.BigDecimal(close.getBody().get("expectedCash").toString())).isEqualByComparingTo("700");
        assertThat(new java.math.BigDecimal(close.getBody().get("variance").toString())).isEqualByComparingTo("-10");
    }

    @Test void failedCheckoutRollsBackEverything() {
        String owner = ownerToken();
        Number id = createProduct(owner, "CHK-2", 3, "50.00");
        assertThat(call(HttpMethod.POST, "/api/sales/checkout", owner, cart(id, 5, "250.00")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);  // insufficient
        assertThat(call(HttpMethod.POST, "/api/sales/checkout", owner, cart(id, 1, "1.00")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);      // wrong payment total
        assertThat(product(owner, id).get("currentStock").toString()).startsWith("3");
        var moves = call(HttpMethod.GET, "/api/inventory/movements?productId=" + id, owner, null);
        assertThat(moves.getBody().get("totalElements")).isEqualTo(1);   // only the opening stock
    }

    @Test void concurrentCheckoutsNeverOversell() throws Exception {
        String owner = ownerToken();
        Number id = createProduct(owner, "CHK-3", 10, "10.00");     
        int threads = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) fs.add(pool.submit(() -> {
            start.await();
            if (call(HttpMethod.POST, "/api/sales/checkout", owner, cart(id, 1, "10.00")).getStatusCode() == HttpStatus.CREATED) success.incrementAndGet();
            return null;
        }));
        start.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(success.get()).isEqualTo(10);
        assertThat(product(owner, id).get("currentStock").toString()).startsWith("0");
    }
}
