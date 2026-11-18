package com.banco.transfer_service.transfer.repository;

import com.banco.transfer_service.transfer.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByIdAndCustomerId(UUID id, UUID customerId);

    List<Transfer> findAllByCustomerIdOrderByCreatedAtDesc(UUID customerId);
}
