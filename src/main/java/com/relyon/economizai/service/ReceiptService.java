package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.AddReceiptItemRequest;
import com.relyon.economizai.dto.request.ConfirmReceiptRequest;
import com.relyon.economizai.dto.request.SubmitReceiptRequest;
import com.relyon.economizai.dto.request.UpdateReceiptItemRequest;
import com.relyon.economizai.dto.response.ConfirmReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.exception.PaywallException;
import com.relyon.economizai.exception.ReceiptAlreadyIngestedException;
import com.relyon.economizai.exception.ReceiptItemNotFoundException;
import com.relyon.economizai.exception.ReceiptNotEditableException;
import com.relyon.economizai.exception.ReceiptNotFoundException;
import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.config.MdcContextFilter;
import com.relyon.economizai.model.Receipt;
import com.relyon.economizai.model.ReceiptItem;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.NotificationType;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.ReceiptItemRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.cache.HouseholdCacheGen;
import com.relyon.economizai.service.canonicalization.CanonicalizationService;
import com.relyon.economizai.service.geo.MarketLocationService;
import com.relyon.economizai.service.geo.MarketNameService;
import com.relyon.economizai.service.notifications.NotificationPayload;
import com.relyon.economizai.service.notifications.NotificationRuleService;
import com.relyon.economizai.service.notifications.NotificationService;
import com.relyon.economizai.service.priceindex.PriceIndexService;
import com.relyon.economizai.service.privacy.LogMasker;
import com.relyon.economizai.service.priceindex.PromoDetector;
import com.relyon.economizai.service.sefaz.ChaveAcessoParser;
import com.relyon.economizai.service.sefaz.FailedParseRecorder;
import com.relyon.economizai.service.sefaz.ParsedReceipt;
import com.relyon.economizai.service.sefaz.SefazIngestionService;
import com.relyon.economizai.service.subscription.Feature;
import com.relyon.economizai.service.subscription.SubscriptionGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final SefazIngestionService sefazIngestionService;
    private final FailedParseRecorder failedParseRecorder;
    private final CanonicalizationService canonicalizationService;
    private final PriceIndexService priceIndexService;
    private final PromoDetector promoDetector;
    private final MarketLocationService marketLocationService;
    private final NotificationService notificationService;
    private final NotificationRuleService notificationRuleService;
    private final HouseholdProductAliasService householdProductAliasService;
    private final HouseholdProductCategoryOverrideService categoryOverrideService;
    private final HouseholdCacheGen householdCacheGen;
    private final MarketNameService marketNameService;
    private final SubscriptionGateService subscriptionGate;

    @Transactional
    public ReceiptResponse submit(User user, SubmitReceiptRequest request) {
        var qrPayload = request.qrPayload();
        var chave = ChaveAcessoParser.extractChave(qrPayload);
        log.info("submit chave={}", LogMasker.chave(chave));

        // Monthly receipt cap (FREE). Counts ALL statuses this calendar month so
        // reject/delete-and-resubmit can't game the limit. PRO bypasses.
        var monthlyLimit = subscriptionGate.monthlyReceiptLimit(user);
        if (monthlyLimit != Integer.MAX_VALUE) {
            var startOfMonth = YearMonth.now().atDay(1).atStartOfDay();
            var thisMonth = receiptRepository.countByUserIdAndCreatedAtGreaterThanEqual(user.getId(), startOfMonth);
            if (thisMonth >= monthlyLimit) {
                log.info("paywall.blocked user={} feature={} thisMonth={} limit={}",
                        LogMasker.email(user.getEmail()), Feature.RECEIPT_UPLOAD_UNLIMITED, thisMonth, monthlyLimit);
                throw new PaywallException(Feature.RECEIPT_UPLOAD_UNLIMITED.name());
            }
        }

        // Per-household uniqueness: a household can't double-import its own
        // CONFIRMED receipts (data has been committed downstream — price
        // observations, audit rows, notifications). But re-submit is allowed
        // when the prior row is in any non-final state — typically PENDING_
        // CONFIRMATION (user closed the app mid-review and wants to retry),
        // REJECTED (user changed their mind), or FAILED_PARSE (parser fix
        // landed). In those cases we discard the stale row so the fresh
        // submission can take its place. Two different households can
        // always both record the same fiscal event regardless.
        receiptRepository.findByHouseholdIdAndChaveAcesso(user.getHousehold().getId(), chave)
                .ifPresent(existing -> {
                    if (existing.getStatus() == ReceiptStatus.CONFIRMED) {
                        log.info("submit rejected: chave {} already CONFIRMED in household {}",
                                LogMasker.chave(chave), user.getHousehold().getId());
                        throw new ReceiptAlreadyIngestedException(chave);
                    }
                    log.info("submit replacing stale chave {} (status_was={}) in household {}",
                            LogMasker.chave(chave), existing.getStatus(), user.getHousehold().getId());
                    receiptRepository.delete(existing);
                    receiptRepository.flush();
                });

        var fetched = sefazIngestionService.fetch(qrPayload);
        ParsedReceipt parsed;
        try {
            parsed = sefazIngestionService.parse(fetched);
        } catch (ReceiptParseException ex) {
            // Record in a separate transaction (REQUIRES_NEW on the recorder
            // bean) so the row survives this method's @Transactional rollback
            // when we rethrow.
            failedParseRecorder.record(user, qrPayload, fetched, ex);
            throw ex;
        }
        var receipt = persistParsed(user, qrPayload, parsed);
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receipt.getId()));
        log.info("submit ok status=PENDING_CONFIRMATION items={} total={} market='{}'",
                receipt.getItems().size(), receipt.getTotalAmount(), receipt.getMarketName());
        return withFriendlyName(user.getHousehold().getId(), receipt, ReceiptResponse.from(receipt));
    }

    @Transactional(readOnly = true)
    public Page<ReceiptSummaryResponse> list(User user,
                                             LocalDateTime from,
                                             LocalDateTime to,
                                             String cnpjEmitente,
                                             List<ProductCategory> categories,
                                             ReceiptStatus status,
                                             String search,
                                             Pageable pageable) {
        var cnpj = Optional.ofNullable(cnpjEmitente).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var trimmedSearch = Optional.ofNullable(search).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var sortedPageable = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Direction.DESC, "issuedAt"))
                : pageable;
        var spec = ReceiptSpecifications.forSearch(
                user.getHousehold().getId(), from, to, cnpj, categories, status, trimmedSearch, true);
        var page = receiptRepository.findAll(spec, sortedPageable);
        var householdId = user.getHousehold().getId();
        var cnpjs = page.getContent().stream()
                .map(Receipt::getCnpjEmitente)
                .filter(c -> c != null)
                .distinct()
                .toList();
        var overrides = marketNameService.resolveNames(householdId, cnpjs);
        return page.map(receipt -> ReceiptSummaryResponse.from(receipt)
                .withMarketFriendlyName(marketNameService.applyOverride(
                        overrides, receipt.getCnpjEmitente(), receipt.getMarketName())));
    }

    @Transactional(readOnly = true)
    public ReceiptResponse get(User user, UUID receiptId) {
        return toResponse(user, loadOwned(user, receiptId));
    }

    /**
     * The household's manual category correction for a receipt item's product —
     * "evidence, not truth": it overrides only this household's view (the global
     * product is untouched). Item must be linked to a product.
     */
    @Transactional
    public ReceiptResponse updateItemCategory(User user, UUID receiptId, UUID itemId, ProductCategory category) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        MDC.put(MdcContextFilter.ITEM_ID, abbrev(itemId));
        var receipt = loadOwned(user, receiptId);
        var item = receipt.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(ReceiptItemNotFoundException::new);
        if (item.getProduct() == null) {
            throw new ReceiptNotEditableException("ITEM_NOT_LINKED_TO_PRODUCT");
        }
        categoryOverrideService.setOverride(user, item.getProduct(), category);
        log.info("item.category.override product={} category={}", item.getProduct().getId(), category);
        return toResponse(user, receipt);
    }

    /** Build a ReceiptResponse with the household's category overrides applied. */
    private ReceiptResponse toResponse(User user, Receipt receipt) {
        var productIds = receipt.getItems().stream()
                .filter(i -> i.getProduct() != null)
                .map(i -> i.getProduct().getId())
                .distinct()
                .toList();
        var overrides = categoryOverrideService.overridesByProduct(user.getHousehold().getId(), productIds);
        return withFriendlyName(user.getHousehold().getId(), receipt, ReceiptResponse.from(receipt, overrides));
    }

    @Transactional
    public ConfirmReceiptResponse confirm(User user, UUID receiptId, ConfirmReceiptRequest request) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        log.info("confirm started");
        var receipt = loadOwned(user, receiptId);
        requirePending(receipt);

        // Apply per-item exclusions BEFORE downstream processing so canonicalization,
        // promo detection, and price-index contributions all skip the excluded rows.
        var excludedIds = request != null && request.excludedItemIds() != null
                ? Set.copyOf(request.excludedItemIds())
                : Set.<UUID>of();
        if (!excludedIds.isEmpty()) {
            var excludedCount = 0;
            for (var item : receipt.getItems()) {
                if (excludedIds.contains(item.getId())) {
                    item.setExcluded(true);
                    excludedCount++;
                }
            }
            log.info("confirm.exclusions applied={}/{} items", excludedCount, receipt.getItems().size());
        }

        receipt.setStatus(ReceiptStatus.CONFIRMED);
        receipt.setConfirmedAt(LocalDateTime.now());
        canonicalizationService.canonicalize(receipt);
        var personalPromos = promoDetector.detectPersonalPromos(receipt);
        priceIndexService.recordContributions(receipt);
        marketLocationService.registerMarketFromReceipt(receipt);
        notifyPersonalPromos(user, receipt, personalPromos);
        var saved = receiptRepository.save(receipt);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("confirm ok status=CONFIRMED personalPromos={}", personalPromos.size());
        return new ConfirmReceiptResponse(
                withFriendlyName(user.getHousehold().getId(), saved, ReceiptResponse.from(saved)), personalPromos);
    }

    private void notifyPersonalPromos(User user, Receipt receipt, List<PromoDetector.PersonalPromo> promos) {
        if (!promos.isEmpty() && !notificationRuleService.isEnabled(user, NotificationType.PROMO_PERSONAL)) {
            log.debug("personal_promo.skipped user_disabled count={}", promos.size());
            return;
        }
        for (var promo : promos) {
            var title = "Você economizou em " + promo.productName();
            var body = String.format("No %s você pagou R$ %s no %s — %s%% abaixo do que normalmente paga.",
                    promo.productName(), promo.paidPrice(), receipt.getMarketName(), promo.savingsPct());
            notificationService.notify(new NotificationPayload(
                    user,
                    NotificationType.PROMO_PERSONAL,
                    title, body,
                    Map.of(
                            "receiptId", receipt.getId().toString(),
                            "productId", promo.productId().toString(),
                            "savingsPct", promo.savingsPct(),
                            "paidPrice", promo.paidPrice(),
                            "historicalMedian", promo.historicalMedian()
                    )
            ));
        }
    }

    /**
     * User-initiated hard delete. Removes the receipt + its items + the
     * audit rows that link the household to anonymized {@code PriceObservation}
     * entries (cascaded by FK). Does NOT delete the observations themselves —
     * once contributed, anonymized price data stays in the community index.
     * This is by design (LGPD right-to-deletion of personal data, while
     * preserving anonymized aggregates) and is enforced by the schema:
     * {@code price_observation_audits.receipt_id} has {@code ON DELETE
     * CASCADE}, but {@code price_observations} has no FK back to receipts.
     * Frees the chave for re-import within the same household.
     */
    @Transactional
    public void delete(User user, UUID receiptId) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        var receipt = loadOwned(user, receiptId);
        var householdId = receipt.getHousehold().getId();
        receiptRepository.delete(receipt);
        householdCacheGen.bump(householdId);
        log.info("delete ok status_was={}", receipt.getStatus());
    }

    /**
     * Admin-only: re-runs parsing on the stored raw HTML, replaces the
     * existing items with the freshly-parsed ones, and resets the receipt
     * to PENDING_CONFIRMATION so the owner can review and re-confirm.
     *
     * <p>Use case: a parser bug is fixed and we want to re-process old
     * receipts without forcing users to re-scan their QR codes.
     *
     * <p>Caveat: if the receipt was previously CONFIRMED, its old
     * PriceObservation rows remain. They'll be joined by new ones when
     * the user re-confirms. Acceptable at admin scale; revisit if this
     * endpoint ever gets bulk usage.
     */
    @Transactional
    public ReceiptResponse reparse(UUID receiptId) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        var receipt = receiptRepository.findById(receiptId).orElseThrow(ReceiptNotFoundException::new);
        if (receipt.getRawHtml() == null || receipt.getRawHtml().isBlank()) {
            throw new ReceiptNotEditableException("RAW_HTML_MISSING");
        }
        log.info("reparse start status_was={} chave={}",
                receipt.getStatus(), LogMasker.chave(receipt.getChaveAcesso()));

        var parsed = sefazIngestionService.reparseStored(
                receipt.getUf(), receipt.getRawHtml(), receipt.getChaveAcesso(), receipt.getSourceUrl());

        receipt.getItems().clear();
        parsed.items().forEach(p -> receipt.addItem(ReceiptItem.builder()
                .lineNumber(p.lineNumber())
                .rawDescription(p.rawDescription())
                .ean(p.ean())
                .quantity(p.quantity())
                .unit(p.unit())
                .unitPrice(p.unitPrice())
                .totalPrice(p.totalPrice())
                .nfcePromoFlag(p.nfcePromoFlag())
                .build()));
        receipt.setMarketName(parsed.marketName());
        receipt.setMarketAddress(parsed.marketAddress());
        receipt.setIssuedAt(parsed.issuedAt());
        receipt.setTotalAmount(parsed.totalAmount());
        receipt.setApproxTaxFederal(parsed.approxTaxFederal());
        receipt.setApproxTaxEstadual(parsed.approxTaxEstadual());
        receipt.setCnpjEmitente(parsed.cnpjEmitente());
        receipt.setStatus(ReceiptStatus.PENDING_CONFIRMATION);
        receipt.setConfirmedAt(null);
        receipt.setParseErrorReason(null);
        var saved = receiptRepository.save(receipt);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("reparse ok items={} total={} market='{}'",
                saved.getItems().size(), saved.getTotalAmount(), saved.getMarketName());
        return withFriendlyName(saved.getHousehold().getId(), saved, ReceiptResponse.from(saved));
    }

    @Transactional
    public ReceiptResponse reject(User user, UUID receiptId) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        var receipt = loadOwned(user, receiptId);
        requirePending(receipt);
        receipt.setStatus(ReceiptStatus.REJECTED);
        var saved = receiptRepository.save(receipt);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("reject ok status=REJECTED");
        return withFriendlyName(user.getHousehold().getId(), saved, ReceiptResponse.from(saved));
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
        // Remember the friendly name household-wide so future receipts of
        // the same Product inherit it. No-op when item isn't linked yet.
        householdProductAliasService.rememberFromItem(receipt.getHousehold(), item);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("item.updated description='{}' qty={} totalPrice={}",
                item.getRawDescription(), item.getQuantity(), item.getTotalPrice());
        return withFriendlyName(user.getHousehold().getId(), receipt, ReceiptResponse.from(receipt));
    }

    /**
     * Add a missing item to a PENDING_CONFIRMATION receipt. Use case: SVRS
     * parser missed a line. Position appended to the end of the existing
     * items list.
     */
    @Transactional
    public ReceiptResponse addItem(User user, UUID receiptId, AddReceiptItemRequest request) {
        MDC.put(MdcContextFilter.RECEIPT_ID, abbrev(receiptId));
        var receipt = loadOwned(user, receiptId);
        requirePending(receipt);
        var nextLine = receipt.getItems().stream()
                .map(ReceiptItem::getLineNumber)
                .filter(n -> n != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
        var item = ReceiptItem.builder()
                .lineNumber(nextLine)
                .rawDescription(request.rawDescription())
                .friendlyDescription(request.friendlyDescription())
                .ean(request.ean())
                .quantity(request.quantity())
                .unit(request.unit())
                .unitPrice(request.unitPrice())
                .totalPrice(request.totalPrice())
                .build();
        receipt.addItem(item);
        receiptItemRepository.save(item);
        householdCacheGen.bump(receipt.getHousehold().getId());
        log.info("item.added line={} description='{}' qty={} totalPrice={}",
                nextLine, item.getRawDescription(), item.getQuantity(), item.getTotalPrice());
        return withFriendlyName(user.getHousehold().getId(), receipt, ReceiptResponse.from(receipt));
    }

    /** Apply the household's custom market display name to the response (sibling field; original untouched). */
    private ReceiptResponse withFriendlyName(UUID householdId, Receipt receipt, ReceiptResponse response) {
        var friendly = marketNameService.resolve(householdId, receipt.getCnpjEmitente(), receipt.getMarketName());
        return response.withMarketFriendlyName(friendly);
    }

    private static String abbrev(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }

    private Receipt loadOwned(User user, UUID receiptId) {
        var receipt = receiptRepository.findByIdWithItemsAndProducts(receiptId)
                .orElseThrow(ReceiptNotFoundException::new);
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
                .approxTaxFederal(parsed.approxTaxFederal())
                .approxTaxEstadual(parsed.approxTaxEstadual())
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
                .nfcePromoFlag(p.nfcePromoFlag())
                .build()));
        return receiptRepository.save(receipt);
    }

    private void applyUpdate(ReceiptItem item, UpdateReceiptItemRequest request) {
        // rawDescription stays immutable — it's the SEFAZ-issued audit text.
        // Display overrides go on friendlyDescription.
        item.setEan(request.ean());
        item.setQuantity(request.quantity());
        item.setUnit(request.unit());
        item.setUnitPrice(request.unitPrice());
        item.setTotalPrice(request.totalPrice());
        if (request.excluded() != null) item.setExcluded(request.excluded());
        if (request.friendlyDescription() != null) {
            item.setFriendlyDescription(request.friendlyDescription().isBlank()
                    ? null : request.friendlyDescription());
        }
    }
}
