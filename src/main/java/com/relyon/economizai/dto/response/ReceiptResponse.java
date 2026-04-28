package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.model.enums.UnidadeFederativa;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ReceiptResponse(
        UUID id,
        String chaveAcesso,
        UnidadeFederativa uf,
        String cnpjEmitente,
        String marketName,
        String marketAddress,
        LocalDateTime issuedAt,
        BigDecimal totalAmount,
        ReceiptStatus status,
        LocalDateTime confirmedAt,
        LocalDateTime createdAt,
        List<ReceiptItemResponse> items
) {
    public static ReceiptResponse from(Receipt receipt) {
        return new ReceiptResponse(
                receipt.getId(),
                receipt.getChaveAcesso(),
                receipt.getUf(),
                receipt.getCnpjEmitente(),
                receipt.getMarketName(),
                receipt.getMarketAddress(),
                receipt.getIssuedAt(),
                receipt.getTotalAmount(),
                receipt.getStatus(),
                receipt.getConfirmedAt(),
                receipt.getCreatedAt(),
                receipt.getItems().stream().map(ReceiptItemResponse::from).toList()
        );
    }
}
