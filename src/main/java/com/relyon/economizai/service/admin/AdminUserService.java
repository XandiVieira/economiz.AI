package com.relyon.economizai.service.admin;

import com.relyon.economizai.dto.response.AdminUserDetailResponse;
import com.relyon.economizai.dto.response.AdminUserDetailResponse.ReceiptCounts;
import com.relyon.economizai.dto.response.AdminUserSummaryResponse;
import com.relyon.economizai.exception.UserNotFoundException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.model.enums.ReceiptStatus;
import com.relyon.economizai.repository.InsightsRepository;
import com.relyon.economizai.repository.ReceiptRepository;
import com.relyon.economizai.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin-only operations on users: paginated list with optional name/email
 * search, and a detail view that bundles household stats + receipt counts +
 * 30-day spend snapshot.
 *
 * <p>Single responsibility: read-only views for ops triage. No mutations
 * here — promotion/deactivation flows would belong in a separate service
 * if/when we add them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final int SPEND_WINDOW_DAYS = 30;

    private final UserRepository userRepository;
    private final ReceiptRepository receiptRepository;
    private final InsightsRepository insightsRepository;

    @Transactional(readOnly = true)
    public Page<AdminUserSummaryResponse> list(String search, Pageable pageable) {
        var trimmed = Optional.ofNullable(search).map(String::trim).filter(s -> !s.isBlank()).orElse(null);
        var sortedPageable = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"))
                : pageable;
        return userRepository.findAll(searchSpec(trimmed), sortedPageable).map(AdminUserSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse get(UUID userId) {
        var user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId.toString()));
        var householdId = user.getHousehold().getId();

        var receipts = countReceiptsByStatus(householdId);
        var spend = insightsRepository.totalSpend(
                householdId, LocalDateTime.now().minusDays(SPEND_WINDOW_DAYS), LocalDateTime.now());
        var memberCount = userRepository.countByHouseholdId(householdId);

        log.info("admin.user.get userId={} household={} memberCount={} confirmed={}",
                userId, householdId, memberCount, receipts.confirmed());
        return AdminUserDetailResponse.from(user, memberCount, receipts, spend);
    }

    private ReceiptCounts countReceiptsByStatus(UUID householdId) {
        return new ReceiptCounts(
                receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.PENDING_CONFIRMATION),
                receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.CONFIRMED),
                receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.REJECTED),
                receiptRepository.countByHouseholdIdAndStatus(householdId, ReceiptStatus.FAILED_PARSE)
        );
    }

    private Specification<User> searchSpec(String search) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (search != null) {
                var like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("email")), like),
                        cb.like(cb.lower(root.get("name")), like)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
