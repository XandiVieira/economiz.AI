package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.OptimizeShoppingListRequest;
import com.relyon.economizai.dto.response.ShoppingPlanResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.shopping.ShoppingListOptimizer;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shopping-list")
@RequiredArgsConstructor
@Tag(name = "Shopping list", description = "Multi-market basket optimization (PRO-52)")
public class ShoppingListController {

    private final ShoppingListOptimizer optimizer;

    @PostMapping("/optimize")
    public ResponseEntity<ShoppingPlanResponse> optimize(@AuthenticationPrincipal User user,
                                                         @Valid @RequestBody OptimizeShoppingListRequest request) {
        return ResponseEntity.ok(optimizer.optimize(user, request));
    }
}
