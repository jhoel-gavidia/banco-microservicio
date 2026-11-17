package com.banco.account_service.ledger.mapper;

import com.banco.account_service.ledger.LedgerEntry;
import com.banco.account_service.ledger.dto.LedgerEntryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface LedgerEntryMapper {

    @Mapping(target = "accountId", source = "account.id")
    LedgerEntryResponse toResponse(LedgerEntry entry);
}
