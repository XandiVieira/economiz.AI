package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.CreateAliasRequest;
import com.relyon.economizai.dto.request.CreateProductRequest;
import com.relyon.economizai.dto.request.UpdateProductRequest;
import com.relyon.economizai.dto.response.ProductResponse;
import com.relyon.economizai.dto.response.UnmatchedItemResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.ProductService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Canonical product registry and alias management")
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<Page<ProductResponse>> search(@RequestParam(required = false) String query,
                                                        @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(productService.search(query, pageable));
    }

    @GetMapping("/unmatched")
    public ResponseEntity<List<UnmatchedItemResponse>> unmatched(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(productService.listUnmatched(user));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(productService.get(id));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(productService.update(id, request));
    }

    @PostMapping("/{id}/aliases")
    public ResponseEntity<ProductResponse> addAlias(@AuthenticationPrincipal User user,
                                                    @PathVariable UUID id,
                                                    @Valid @RequestBody CreateAliasRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.addAlias(user, id, request));
    }
}
