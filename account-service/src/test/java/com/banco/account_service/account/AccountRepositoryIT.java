package com.banco.account_service.account;

import com.banco.account_service.account.repository.AccountRepository;
import com.banco.account_service.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Sql(scripts = "/clean-db.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AccountRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID CUSTOMER_ID = UUID.randomUUID();

    @Test
    void persistAndRead_generatedCreatedAtIsPopulated() {
        Account account = Account.open(CUSTOMER_ID, "IT-001", "EUR");

        Account saved = accountRepository.saveAndFlush(account);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void currencyCheck_rejectsLowerCaseFromDatabase() {
        // SQL nativo: asi el rechazo viene del CHECK de PostgreSQL,
        // no de la validacion de Account.open().
        assertThatThrownBy(() -> insertRaw("IT-002", "eur"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueNumber_rejectsDuplicateFromDatabase() {
        insertRaw("IT-003", "EUR");

        assertThatThrownBy(() -> insertRaw("IT-003", "EUR"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ledger_isImmutableEvenForDirectSql() {
        UUID accountId = insertRawReturningId("IT-004", "EUR");

        jdbcTemplate.update(
                "INSERT INTO ledger_entries (account_id, type, amount, balance_after, reference) " +
                "VALUES (?, 'CREDIT', 10.0000, 10.0000, 'ref-1')",
                accountId
        );

        // Un RAISE EXCEPTION de PL/pgSQL (SQL state P0001) no es un constraint
        // declarativo: Spring lo traduce a UncategorizedSQLException, no a
        // DataIntegrityViolationException. Verificamos el mensaje del trigger.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_entries SET amount = 20.0000 WHERE reference = 'ref-1'"
        ))
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("inmutable");
    }

    private void insertRaw(String number, String currency) {
        jdbcTemplate.update(
                "INSERT INTO accounts (customer_id, number, currency) VALUES (?, ?, ?)",
                CUSTOMER_ID, number, currency
        );
    }

    private UUID insertRawReturningId(String number, String currency) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO accounts (customer_id, number, currency) VALUES (?, ?, ?) RETURNING id",
                UUID.class, CUSTOMER_ID, number, currency
        );
    }
}
