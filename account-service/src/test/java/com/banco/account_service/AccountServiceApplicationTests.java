package com.banco.account_service;

import com.banco.account_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Verifica que el contexto arranca contra un PostgreSQL real y efimero.
 * Ademas, con ddl-auto=validate, comprueba que la entidad Account coincide
 * con el schema que Flyway crea en V1/V2/V3.
 */
class AccountServiceApplicationTests extends PostgresIntegrationTest {

    @Test
    void contextLoads() {
    }
}
