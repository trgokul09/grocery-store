package com.grocery;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class PurchaseFlowIT extends AbstractIT {

    @Test @SuppressWarnings("unchecked")
    void poWorkflowReceivingUpdatesStockAndSupplierLedger() {
        String owner = ownerToken(), staff = createUser(owner, "STAFF");
        Number pid = createProduct(owner, "PO-1", 0, "25.00");
        var sup = call(HttpMethod.POST, "/api/suppliers", owner, Map.of("name", "Fresh Co", "paymentTermsDays", 15, "openingBalance", 100));
        Number sid = (Number) sup.getBody().get("id");
        assertThat(sup.getBody().get("approved")).isEqualTo(true);                    // owner-created suppliers are pre-approved

        var po = call(HttpMethod.POST, "/api/purchase-orders", owner, Map.of("supplierId", sid, "items", List.of(Map.of("productId", pid, "quantity", 10, "unitCost", 8))));
        Number poId = (Number) po.getBody().get("id");
        assertThat(po.getBody().get("status")).isEqualTo("DRAFT");

        // cannot be received before approval
        var early = call(HttpMethod.POST, "/api/purchase-orders/" + poId + "/receive", staff, Map.of("lines", List.of(Map.of("itemId", 1, "receivedQty", 1))));
        assertThat(early.getStatusCode()).isNotEqualTo(HttpStatus.OK);

        assertThat(call(HttpMethod.POST, "/api/purchase-orders/" + poId + "/submit", owner, null).getBody().get("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(call(HttpMethod.POST, "/api/purchase-orders/" + poId + "/approve", owner, null).getBody().get("status")).isEqualTo("APPROVED");

        var detail = call(HttpMethod.GET, "/api/purchase-orders/" + poId, staff, null).getBody();
        Number itemId = (Number) ((List<Map<String, Object>>) detail.get("items")).get(0).get("id");

        // staff receives 6, 1 damaged -> 5 good units * 8 = 40 payable; staff may not pay suppliers
        var rcv = call(HttpMethod.POST, "/api/purchase-orders/" + poId + "/receive", staff,
            Map.of("lines", List.of(Map.of("itemId", itemId, "receivedQty", 6, "damagedQty", 1))));
        assertThat(rcv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rcv.getBody().get("status")).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(product(owner, pid).get("currentStock").toString()).startsWith("5");

        // outstanding = 100 opening + 40 purchase
        assertThat(new BigDecimal(call(HttpMethod.GET, "/api/suppliers/" + sid, owner, null).getBody().get("outstanding").toString())).isEqualByComparingTo("140");

        // owner pays 40 by UPI, then cannot overpay
        assertThat(call(HttpMethod.POST, "/api/suppliers/" + sid + "/payments", owner, Map.of("amount", 40, "method", "UPI")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.POST, "/api/suppliers/" + sid + "/payments", owner, Map.of("amount", 1000, "method", "CASH")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(new BigDecimal(call(HttpMethod.GET, "/api/suppliers/" + sid, owner, null).getBody().get("outstanding").toString())).isEqualByComparingTo("100");

        // remaining 4 -> RECEIVED; cannot exceed ordered
        var over = call(HttpMethod.POST, "/api/purchase-orders/" + poId + "/receive", staff, Map.of("lines", List.of(Map.of("itemId", itemId, "receivedQty", 5))));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var done = call(HttpMethod.POST, "/api/purchase-orders/" + poId + "/receive", staff, Map.of("lines", List.of(Map.of("itemId", itemId, "receivedQty", 4))));
        assertThat(done.getBody().get("status")).isEqualTo("RECEIVED");
    }
}
