package com.grocery;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SecurityAndProductIT extends AbstractIT {

    @Test void unauthenticatedRequestsAreRejected() {
        assertThat(rest.getForEntity("/api/products", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test void staffCannotEditProductMasterDataOrSeeAuditLogs() {
        String owner = ownerToken(), staff = createUser(owner, "STAFF");
        var create = call(HttpMethod.POST, "/api/products", staff, Map.of("name", "X", "sku", "SEC-1", "purchasePrice", 1, "sellingPrice", 2, "unit", "PCS"));
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/audit-logs", staff, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/audit-logs", owner, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void duplicateSkuIsRejectedAndDeleteIsSoft() {
        String owner = ownerToken();
        Number id = createProduct(owner, "DUP-1", 5, "20.00");
        var dup = call(HttpMethod.POST, "/api/products", owner, Map.of("name", "Again", "sku", "dup-1", "purchasePrice", 1, "sellingPrice", 2, "unit", "PCS"));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(call(HttpMethod.DELETE, "/api/products/" + id, owner, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> p = product(owner, id);                // still exists, only deactivated
        assertThat(p.get("active")).isEqualTo(false);
        assertThat(p.get("currentStock").toString()).startsWith("5");
    }

    @Test void openingStockIsLoggedAsMovementAndStockCannotBeEditedViaProductUpdate() {
        String owner = ownerToken();
        Number id = createProduct(owner, "MOV-1", 7, "30.00");
        var moves = call(HttpMethod.GET, "/api/inventory/movements?productId=" + id, owner, null);
        assertThat(moves.getBody().get("totalElements")).isEqualTo(1);

        call(HttpMethod.PUT, "/api/products/" + id, owner, Map.of("name", "Renamed", "sku", "MOV-1", "purchasePrice", 10, "sellingPrice", 31, "unit", "PCS", "openingStock", 999));
        assertThat(product(owner, id).get("currentStock").toString()).startsWith("7");
    }
}
