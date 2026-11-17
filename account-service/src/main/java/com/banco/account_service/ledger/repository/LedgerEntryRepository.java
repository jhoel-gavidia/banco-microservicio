package com.banco.account_service.ledger.repository;

import com.banco.account_service.ledger.LedgerEntry;
import com.banco.account_service.ledger.LedgerEntryType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByAccountIdOrderByEntrySeqDesc(UUID accountId);

    boolean existsByAccountIdAndReferenceAndType(
            UUID accountId,
            String reference,
            LedgerEntryType type
    );
}
