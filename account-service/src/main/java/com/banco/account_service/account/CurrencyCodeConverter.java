package com.banco.account_service.account;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class CurrencyCodeConverter implements AttributeConverter<CurrencyCode, String> {

    @Override
    public String convertToDatabaseColumn(CurrencyCode attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public CurrencyCode convertToEntityAttribute(String dbData) {
        return dbData == null ? null : CurrencyCode.of(dbData);
    }
}
