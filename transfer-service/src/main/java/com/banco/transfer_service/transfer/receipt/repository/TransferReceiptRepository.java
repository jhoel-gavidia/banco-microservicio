package com.banco.transfer_service.transfer.receipt.repository;

import com.banco.transfer_service.transfer.receipt.domain.TransferReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferReceiptRepository extends JpaRepository<TransferReceipt, UUID> {

    Optional<TransferReceipt> findByIdAndTransferId(UUID id, UUID transferId);

    Optional<TransferReceipt> findByIdAndTransferIdAndCustomerId(UUID id, UUID transferId, UUID customerId);

    List<TransferReceipt> findAllByTransferIdOrderByCreatedAtDescIdDesc(UUID transferId);

    List<TransferReceipt> findAllByTransferIdAndCustomerIdOrderByCreatedAtDescIdDesc(
            UUID transferId,
            UUID customerId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            WITH candidates AS (
                SELECT id
                FROM transfer_receipts
                WHERE status = 'PENDING'
                  AND next_attempt_at <= now()
                  AND (lease_expires_at IS NULL OR lease_expires_at <= now())
                ORDER BY next_attempt_at, created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT :limit
            )
            UPDATE transfer_receipts r
            SET lease_owner = :workerId,
                lease_expires_at = now() + make_interval(secs => :leaseSeconds),
                next_attempt_at = now() + make_interval(secs => :leaseSeconds),
                attempts = CASE
                    WHEN r.attempts < :maxAttempts THEN r.attempts + 1
                    ELSE r.attempts
                END,
                updated_at = now()
            FROM candidates
            WHERE r.id = candidates.id
            RETURNING r.*
            """, nativeQuery = true)
    List<TransferReceipt> claimPending(
            @Param("workerId") UUID workerId,
            @Param("limit") int limit,
            @Param("leaseSeconds") long leaseSeconds,
            @Param("maxAttempts") int maxAttempts
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE transfer_receipts
            SET status = 'ACTIVE',
                storage_version_id = :storageVersionId,
                confirmed_at = now(),
                failure_reason = NULL,
                last_error = NULL,
                lease_owner = NULL,
                lease_expires_at = NULL,
                next_attempt_at = NULL,
                updated_at = now()
            WHERE id = :id
              AND status = 'PENDING'
              AND lease_owner = :workerId
              AND lease_expires_at > now()
            """, nativeQuery = true)
    int markActive(
            @Param("id") UUID id,
            @Param("workerId") UUID workerId,
            @Param("storageVersionId") String storageVersionId
    );



    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE transfer_receipts
            SET status = 'FAILED',
                failure_reason = :failureReason,
                last_error = :lastError,
                lease_owner = NULL,
                lease_expires_at = NULL,
                next_attempt_at = NULL,
                updated_at = now()
            WHERE id = :id
              AND status = 'PENDING'
              AND lease_owner = :workerId
              AND lease_expires_at > now()
            """, nativeQuery = true)
    int markFailed(
            @Param("id") UUID id,
            @Param("workerId") UUID workerId,
            @Param("failureReason") String failureReason,
            @Param("lastError") String lastError
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE transfer_receipts
            SET next_attempt_at = now() + make_interval(secs => :backoffSeconds),
                last_error = :lastError,
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = now()
            WHERE id = :id
              AND status = 'PENDING'
              AND lease_owner = :workerId
              AND lease_expires_at > now()
            """, nativeQuery = true)
    int reschedule(
            @Param("id") UUID id,
            @Param("workerId") UUID workerId,
            @Param("backoffSeconds") long backoffSeconds,
            @Param("lastError") String lastError
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE transfer_receipts
            SET lease_expires_at = now() + make_interval(secs => :leaseSeconds),
                next_attempt_at = now() + make_interval(secs => :leaseSeconds),
                updated_at = now()
            WHERE id IN (:ids)
              AND status = 'PENDING'
              AND lease_owner = :workerId
              AND lease_expires_at > now()
            """, nativeQuery = true)
    int renewLease(
            @Param("ids") Collection<UUID> ids,
            @Param("workerId") UUID workerId,
            @Param("leaseSeconds") long leaseSeconds
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE transfer_receipts
            SET next_attempt_at = now() + make_interval(secs => :backoffSeconds),
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = now()
            WHERE id = :id
              AND status = 'PENDING'
              AND lease_owner = :workerId
              AND lease_expires_at > now()
            """, nativeQuery = true)
    int releaseAmbiguousLease(
            @Param("id") UUID id,
            @Param("workerId") UUID workerId,
            @Param("backoffSeconds") long backoffSeconds
    );
}
