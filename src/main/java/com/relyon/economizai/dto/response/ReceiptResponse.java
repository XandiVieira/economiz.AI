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
        BigDecimal householdTotalAmount,
        BigDecimal approxTaxFederal,
        BigDecimal approxTaxEstadual,
        BigDecimal approxTaxTotal,
        ReceiptStatus status,
        LocalDateTime confirmedAt,
        LocalDateTime createdAt,
        List<ReceiptItemResponse> items
) {
    public static ReceiptResponse from(Receipt receipt) {
        var householdTotal = receipt.getItems().stream()
                .filter(i -> !i.isExcluded())
                .map(i -> i.getTotalPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReceiptResponse(
                receipt.getId(),
                receipt.getChaveAcesso(),
                receipt.getUf(),
                receipt.getCnpjEmitente(),
                receipt.getMarketName(),
                receipt.getMarketAddress(),
                receipt.getIssuedAt(),
                receipt.getTotalAmount(),
                householdTotal,
                receipt.getApproxTaxFederal(),
                receipt.getApproxTaxEstadual(),
                approxTaxTotal(receipt),
                receipt.getStatus(),
                receipt.getConfirmedAt(),
                receipt.getCreatedAt(),
                receipt.getItems().stream().map(ReceiptItemResponse::from).toList()
        );
    }

    private static BigDecimal approxTaxTotal(Receipt receipt) {
        var fed = receipt.getApproxTaxFederal();
        var est = receipt.getApproxTaxEstadual();
        if (fed == null && est == null) return null;
        return (fed == null ? BigDecimal.ZERO : fed).add(est == null ? BigDecimal.ZERO : est);
    }
}
