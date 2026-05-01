package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.SubmitReceiptRequest;
import com.relyon.economizai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizai.dto.response.ConfirmReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.exception.ReceiptAlreadyIngestedException;
import com.relyon.economizai.exception.ReceiptItemNotFoundException;
import com.relyon.economizai.exception.ReceiptNotEditableException;
import com.relyon.economizai.exception.ReceiptNotFoundException;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.config.MdcContextFilter;
import com.relyon.economizai.service.canonicalization.CanonicalizationService;
import com.relyon.economizai.service.priceindex.PriceIndexService;
import com.relyon.economizai.service.priceindex.PromoDetector;
import com.relyon.economizai.service.sefaz.ChaveAcessoParser;
import com.relyon.economizai.service.sefaz.ParsedReceipt;
import com.relyon.economizai.service.sefaz.SefazIngestionService;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final SefazIngestionService sefazIngestionService;
    private final CanonicalizationService canonicalizationService;
    private final PriceIndexService priceIndexService;
    private final PromoDetector promoDetector;

    @Transactional
    public ReceiptResponse submit(User user, SubmitReceiptRequest request) {
        var qrPayload = request.qrPayload();
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        log.info("submit chave={}", chave);

        if (receiptRepository.existsByChaveAcesso(chave)) {
            log.info("submit rejected: chave {} already ingested", chave);
            throw new ReceiptAlreadyIngestedException(chave);
        }

        var parsed = sefazIngestionService.ingest(qrPayload);
        var receipt = persistParsed(user, qrPayload, parsed);
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receipt.getId()));
        log.info("submit ok status=PENDING_CONFIRMATION items={} total={} market='{}'",
                receipt.getItems().size(), receipt.getTotalAmount(), receipt.getMarketName());
        return ReceiptResponse.from(receipt);
    }

    @Transactional(readOnly = true)
    public Page<ReceiptSummaryResponse> list(User user,
                                             LocalDateTime from,
                                             LocalDateTime to,
                                             String cnpjEmitente,
                                             ProductCategory category,
                                             Pageable pageable) {
        var cnpj = Optional.ofNullable(cnpjEmitente).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var sortedPageable = pageable.getSort().isUnsorted()
                ? org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "issuedAt"))
                : pageable;
        return receiptRepository
                .findAll(filtered(user.getHousehold().getId(), from, to, cnpj, category), sortedPageable)
                .map(ReceiptSummaryResponse::from);
    }

    private Specification<com.relyon.economizai.model.Receipt> filtered(UUID householdId,
                                                                        LocalDateTime from,
                                                                        LocalDateTime to,
                                                                        String cnpj,
                                                                        ProductCategory category) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("household").get("id"), householdId));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("issuedAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("issuedAt"), to));
            if (cnpj != null) predicates.add(cb.equal(root.get("cnpjEmitente"), cnpj));
            if (category != null) {
                if (query != null) query.distinct(true);
                var items = root.join("items", JoinType.INNER);
                var product = items.join("product", JoinType.INNER);
                predicates.add(cb.equal(product.get("category"), category));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public ReceiptResponse get(User user, UUID receiptId) {
        return ReceiptResponse.from(loadOwned(user, receiptId));
    }

    @Transactional
    public ConfirmReceiptResponse confirm(User user, UUID receiptId) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        log.info("confirm started");
        var receipt = loadOwned(user, receiptId);
        requirePending(receipt);
        receipt.setStatus(ReceiptStatus.CONFIRMED);
        receipt.setConfirmedAt(LocalDateTime.now());
        canonicalizationService.canonicalize(receipt);
        var personalPromos = promoDetector.detectPersonalPromos(receipt);
        priceIndexService.recordContributions(receipt);
        var saved = receiptRepository.save(receipt);
        log.info("confirm ok status=CONFIRMED personalPromos={}", personalPromos.size());
        return new ConfirmReceiptResponse(ReceiptResponse.from(saved), personalPromos);
    }

    @Transactional
    public ReceiptResponse reject(User user, UUID receiptId) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        var receipt = loadOwned(user, receiptId);
        requirePending(receipt);
        receipt.setStatus(ReceiptStatus.REJECTED);
        var saved = receiptRepository.save(receipt);
        log.info("reject ok status=REJECTED");
        return ReceiptResponse.from(saved);
    }

    @Transactional
    public ReceiptResponse updateItem(User user, UUID receiptId, UUID itemId, UpdateReceiptItemRequest request) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        MDC.put(MdcContextFilter.ITEM_ID, abbrev(itemId));
        var receipt = loadOwned(user, receiptId);
        requirePending(receipt);
        var item = receipt.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(ReceiptItemNotFoundException::new);
        applyUpdate(item, request);
        receiptItemRepository.save(item);
        log.info("item.updated description='{}' qty={} totalPrice={}",
                item.getRawDescription(), item.getQuantity(), item.getTotalPrice());
        return ReceiptResponse.from(receipt);
    }

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }

    private Receipt loadOwned(User user, UUID receiptId) {
        var receipt = receiptRepository.findById(receiptId).orElseThrow(ReceiptNotFoundException::new);
        if (!receipt.getHousehold().getId().equals(user.getHousehold().getId())) {
            throw new ReceiptNotFoundException();
        }
        return receipt;
    }

    private void requirePending(Receipt receipt) {
        if (receipt.getStatus() != ReceiptStatus.PENDING_CONFIRMATION) {
            throw new ReceiptNotEditableException(receipt.getStatus().name());
        }
    }

    private Receipt persistParsed(User user, String qrPayload, ParsedReceipt parsed) {
        var receipt = Receipt.builder()
                .user(user)
                .household(user.getHousehold())
                .chaveAcesso(parsed.chaveAcesso())
                .uf(ChaveAcessoParser.extractUf(parsed.chaveAcesso()))
                .cnpjEmitente(parsed.cnpjEmitente())
                .marketName(parsed.marketName())
                .marketAddress(parsed.marketAddress())
                .issuedAt(parsed.issuedAt())
                .totalAmount(parsed.totalAmount())
                .qrPayload(qrPayload)
                .sourceUrl(parsed.sourceUrl())
                .rawHtml(parsed.rawHtml())
                .status(ReceiptStatus.PENDING_CONFIRMATION)
                .build();
        parsed.items().forEach(p -> receipt.addItem(ReceiptItem.builder()
                .lineNumber(p.lineNumber())
                .rawDescription(p.rawDescription())
                .ean(p.ean())
                .quantity(p.quantity())
                .unit(p.unit())
                .unitPrice(p.unitPrice())
                .totalPrice(p.totalPrice())
                .build()));
        return receiptRepository.save(receipt);
    }

    private void applyUpdate(ReceiptItem item, UpdateReceiptItemRequest request) {
        item.setRawDescription(request.rawDescription());
        item.setEan(request.ean());
        item.setQuantity(request.quantity());
        item.setUnit(request.unit());
        item.setUnitPrice(request.unitPrice());
        item.setTotalPrice(request.totalPrice());
    }
}
