package com.banco.transfer_service;

import com.banco.transfer_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Verifica que el contexto arranca contra un PostgreSQL real y efimero.
 * Con ddl-auto=validate, tambien comprueba que las entidades coinciden con el schema de Flyway.
 */
class TransferServiceApplicationTests extends PostgresIntegrationTest {

	@Test
	void contextLoads() {
	}

}
