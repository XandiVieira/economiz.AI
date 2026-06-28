package com.relyon.economizai.service;

import com.relyon.economizai.dto.response.ProductResponse;
import com.relyon.economizai.exception.ProductNotFoundException;
import com.relyon.economizai.model.ProductRecentView;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.ProductRecentViewRepository;
import com.relyon.economizai.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductRecentViewService {

    static final int DEFAULT_LIMIT = 10;

    private final ProductRecentViewRepository recentViewRepository;
    private final ProductRepository productRepository;

    @Transactional
    public void track(User user, UUID productId) {
        var product = productRepository.findById(productId)
                .orElseThrow(ProductNotFoundException::new);
        var view = recentViewRepository.findByUserIdAndProductId(user.getId(), productId)
                .orElseGet(() -> ProductRecentView.builder()
                        .user(user)
                        .product(product)
                        .build());
        view.setViewedAt(LocalDateTime.now());
        recentViewRepository.save(view);
        log.debug("product.view.tracked user={} product={}", user.getId(), productId);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> listRecent(User user, int limit) {
        return recentViewRepository
                .findRecentByUserId(user.getId(), PageRequest.of(0, limit))
                .stream()
                .map(view -> ProductResponse.from(view.getProduct()))
                .toList();
    }
}
