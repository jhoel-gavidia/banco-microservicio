package com.banco.transfer_service.transfer.mapper;

import com.banco.transfer_service.transfer.domain.Transfer;
import com.banco.transfer_service.transfer.dto.TransferResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TransferMapper {

    @Mapping(target = "amount", source = "amount.amount")
    @Mapping(target = "currency", source = "amount.currency")
    TransferResponse toResponse(Transfer transfer);
}
