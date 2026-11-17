package com.banco.account_service.account.repository;

import com.banco.account_service.account.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    boolean existsByNumber(String number);
}
