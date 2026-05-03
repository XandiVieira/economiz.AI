package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.response.ReceiptResponse;
import com.relyon.economizai.dto.response.ReceiptSummaryResponse;
import com.relyon.economizai.exception.ReceiptNotFoundException;
import com.relyon.economizai.model.enums.ProductCategory;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.service.ReceiptSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-only operations on receipts: cross-household paginated search and
 * detail-by-id without the per-household ownership check that
 * {@link com.relyon.economizai.service.ReceiptService} enforces for
 * regular users. FAILED_PARSE rows are visible to admins (they're useful
 * for parser debugging) but hidden from end users.
 *
 * <p>Single responsibility: read views over the entire receipt corpus for
 * ops triage. Mutations on receipts (reparse, etc.) live elsewhere.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminReceiptService {

    private final ReceiptRepository receiptRepository;

    @Transactional(readOnly = true)
    public Page<ReceiptSummaryResponse> list(LocalDateTime from,
                                             LocalDateTime to,
                                             String marketCnpj,
                                             ProductCategory category,
                                             String search,
                                             UUID householdId,
                                             Pageable pageable) {
        var trimmedCnpj = Optional.ofNullable(marketCnpj).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var trimmedSearch = Optional.ofNullable(search).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var sortedPageable = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "issuedAt"))
                : pageable;
        // Admin sees FAILED_PARSE rows too — useful for parser triage.
        var spec = ReceiptSpecifications.forSearch(
                householdId, from, to, trimmedCnpj, category, trimmedSearch, false);
        return receiptRepository.findAll(spec, sortedPageable).map(ReceiptSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public ReceiptResponse get(UUID receiptId) {
        var receipt = receiptRepository.findById(receiptId).orElseThrow(ReceiptNotFoundException::new);
        return ReceiptResponse.from(receipt);
    }
}
