package com.grocery;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.Map;
import java.util.UUID;

/** Needs Docker (Testcontainers starts a throw-away PostgreSQL 15). Run with: mvn verify */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.owner.password=OwnerPass#1")
@Testcontainers
public abstract class AbstractIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired protected TestRestTemplate rest;
    protected static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    protected String login(String user, String pass) {
        var res = rest.postForEntity("/api/auth/login", Map.of("username", user, "password", pass), Map.class);
        return (String) res.getBody().get("token");
    }
    protected String ownerToken() { return login("owner", "OwnerPass#1"); }

    protected HttpHeaders auth(String token) { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(token); h.setContentType(MediaType.APPLICATION_JSON); return h; }

    protected ResponseEntity<Map<String, Object>> call(HttpMethod m, String url, String token, Object body) {
        return rest.exchange(url, m, new HttpEntity<>(body, auth(token)), MAP);
    }

    protected String createUser(String ownerToken, String role) {
        String name = role.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 6);
        var r = call(HttpMethod.POST, "/api/users", ownerToken, Map.of("username", name, "password", "Passw0rd!x", "fullName", "Test " + role, "role", role));
        if (r.getStatusCode() != HttpStatus.CREATED) throw new IllegalStateException(String.valueOf(r.getBody()));
        return login(name, "Passw0rd!x");
    }

    protected Number createProduct(String ownerToken, String sku, int stock, String price) {
        var r = call(HttpMethod.POST, "/api/products", ownerToken, Map.of("name", "Item " + sku, "sku", sku, "purchasePrice", "10.00", "sellingPrice", price,
            "unit", "PCS", "minStockLevel", 1, "openingStock", stock));
        if (r.getStatusCode() != HttpStatus.CREATED) throw new IllegalStateException(String.valueOf(r.getBody()));
        return (Number) r.getBody().get("id");
    }

    protected Map<String, Object> product(String token, Number id) { return call(HttpMethod.GET, "/api/products/" + id, token, null).getBody(); }
}
