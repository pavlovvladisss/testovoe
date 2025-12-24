package com.example.testovoe.wallet.controller;

import com.example.testovoe.wallet.dto.WalletBalanceResponse;
import com.example.testovoe.wallet.dto.WalletOperationRequest;
import com.example.testovoe.wallet.dto.WalletOperationResponse;
import com.example.testovoe.wallet.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @PostMapping
    public ResponseEntity<WalletOperationResponse> processOperation(
            @Valid @RequestBody WalletOperationRequest request) {

        log.info("Received operation request: {}", request);

        WalletOperationResponse response = walletService.processOperation(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{walletId}")
    public ResponseEntity<WalletBalanceResponse> getWalletBalance(
            @PathVariable UUID walletId) {

        log.debug("Getting balance for wallet: {}", walletId);

        WalletBalanceResponse response = walletService.getWalletBalance(walletId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{walletId}/exists")
    public ResponseEntity<Boolean> walletExists(@PathVariable UUID walletId) {
        boolean exists = walletService.walletExists(walletId);
        return ResponseEntity.ok(exists);
    }
}
