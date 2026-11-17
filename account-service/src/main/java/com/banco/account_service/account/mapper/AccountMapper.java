package com.banco.account_service.account.mapper;

import com.banco.account_service.account.Account;
import com.banco.account_service.account.dto.AccountResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AccountMapper {

    AccountResponse toResponse(Account account);
}
