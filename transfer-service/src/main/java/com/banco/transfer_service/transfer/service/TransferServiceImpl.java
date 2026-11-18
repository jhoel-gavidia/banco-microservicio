package com.banco.transfer_service.transfer.service;

import com.banco.transfer_service.idempotency.IdempotencyService;
import com.banco.transfer_service.idempotency.IdempotencyService.StoredResponse;
import com.banco.transfer_service.security.CustomerContext;
import com.banco.transfer_service.transfer.domain.Money;
import com.banco.transfer_service.transfer.domain.Transfer;
import com.banco.transfer_service.transfer.dto.CreateTransferRequest;
import com.banco.transfer_service.transfer.dto.TransferResponse;
import com.banco.transfer_service.transfer.mapper.TransferMapper;
import com.banco.transfer_service.transfer.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransferServiceImpl implements TransferService {

    private final TransferRepository repository;
    private final TransferMapper mapper;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public TransferResponse create(
            CreateTransferRequest request,
            CustomerContext customer,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }

        Money money = Money.of(request.amount(), request.currency());
        String requestHash = hash(customer.customerId(), request, money);

        Optional<StoredResponse> replay = idempotencyService.begin(
                customer.customerId(), idempotencyKey, requestHash
        );
        if (replay.isPresent()) {
            return deserialize(replay.get().body());
        }

        Transfer transfer = Transfer.initiate(
                customer.customerId(),
                request.sourceAccountId(),
                request.targetAccountId(),
                money,
                request.reference()
        );

        Transfer saved = repository.saveAndFlush(transfer);
        TransferResponse response = mapper.toResponse(saved);

        idempotencyService.complete(
                customer.customerId(), idempotencyKey, 201, serialize(response)
        );

        return response;
    }

    private String hash(UUID customerId, CreateTransferRequest request, Money money) {
        String payload = customerId + "|"
                + request.sourceAccountId() + "|"
                + request.targetAccountId() + "|"
                + money.getAmount().stripTrailingZeros().toPlainString() + "|"
                + money.getCurrency() + "|"
                + request.reference();

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private TransferResponse deserialize(String body) {
        try {
            return objectMapper.readValue(body, TransferResponse.class);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Stored idempotent response is unreadable", ex);
        }
    }

    private String serialize(TransferResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not store idempotent response", ex);
        }
    }
}
