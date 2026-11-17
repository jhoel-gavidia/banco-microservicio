package com.banco.account_service.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base para tests de integracion contra un PostgreSQL real y efimero.
 * El contenedor es estatico: una sola instancia se comparte entre todos
 * los tests que extienden esta clase (no se reinicia por metodo).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("accounts_test")
                    .withUsername("accounts_test")
                    .withPassword("accounts_test");
                    // .withReuse(true) // opcional: requiere testcontainers.reuse.enable=true

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }
}
