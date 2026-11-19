package com.banco.transfer_service.transfer.repository;

import com.banco.transfer_service.transfer.domain.Transfer;
import com.banco.transfer_service.transfer.domain.TransferStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdAndCustomerId(UUID id, UUID customerId);

    List<Transfer> findAllByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    List<Transfer> findAllByOrderByCreatedAtDesc();

    @Query("""
            select t.id from Transfer t
            where t.status in :statuses
              and (t.nextAttemptAt is null or t.nextAttemptAt <= :now)
            order by t.createdAt asc, t.id asc
            """)
    List<UUID> findProcessableIds(
            @Param("statuses") Collection<TransferStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );
}
