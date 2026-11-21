package com.banco.api_gateway.route;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class GatewayRoutesTest {

    private static final String TOKEN = "route-token";
    private static final HttpServer UPSTREAM;
    private static final List<RecordedRequest> REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger TRANSFER_POSTS = new AtomicInteger();

    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            UPSTREAM.createContext("/", GatewayRoutesTest::handleUpstream);
            UPSTREAM.start();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo iniciar el servidor de test", exception);
        }
    }

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        String baseUrl = "http://localhost:" + UPSTREAM.getAddress().getPort();
        registry.add("spring.cloud.gateway.server.webmvc.routes[0].id", () -> "account-service");
        registry.add("spring.cloud.gateway.server.webmvc.routes[0].uri", () -> baseUrl);
        registry.add("spring.cloud.gateway.server.webmvc.routes[0].predicates[0]",
                () -> "Path=/api/v1/accounts,/api/v1/accounts/**");
        registry.add("spring.cloud.gateway.server.webmvc.routes[1].id", () -> "transfer-service");
        registry.add("spring.cloud.gateway.server.webmvc.routes[1].uri", () -> baseUrl);
        registry.add("spring.cloud.gateway.server.webmvc.routes[1].predicates[0]",
                () -> "Path=/api/v1/transfers,/api/v1/transfers/**");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void stubJwt() {
        Jwt jwt = Jwt.withTokenValue(TOKEN)
                .header("alg", "none")
                .claim("sub", "route-test")
                .claim("scope", "transfer.write")
                .claim("realm_access", Map.of("roles", List.of("ACCOUNT_READ", "TRANSFER_WRITE")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode(TOKEN)).thenReturn(jwt);
        REQUESTS.clear();
        TRANSFER_POSTS.set(0);
    }

    @Test
    void accountRequestIsProxiedWithCorrelationHeader() {
        String correlationId = UUID.randomUUID().toString();
        HttpHeaders headers = bearerHeaders();
        headers.set("X-Correlation-Id", correlationId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/accounts/{accountId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class,
                UUID.randomUUID()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(correlationId);
        assertThat(REQUESTS).anySatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.path()).startsWith("/api/v1/accounts/");
            assertThat(request.correlationId()).isEqualTo(correlationId);
        });
    }

    @Test
    void failedTransferPostIsNotRetried() {
        int before = TRANSFER_POSTS.get();
        HttpHeaders headers = bearerHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(TRANSFER_POSTS.get() - before).isEqualTo(1);
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    private static void handleUpstream(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String correlationId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
        REQUESTS.add(new RecordedRequest(method, path, correlationId));

        int status = "POST".equals(method) && path.equals("/api/v1/transfers")
                ? 500
                : 200;
        byte[] body = "{\"upstream\":true}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        if (status == 500) {
            TRANSFER_POSTS.incrementAndGet();
        }
    }

    private HttpHeaders bearerHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TOKEN);
        return headers;
    }

    private record RecordedRequest(String method, String path, String correlationId) {
    }
}