package ru.oksenyuk.anton.wallet.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.oksenyuk.anton.wallet.dto.WalletBalanceResponse;
import ru.oksenyuk.anton.wallet.dto.WalletOperationRequest;
import ru.oksenyuk.anton.wallet.dto.WalletOperationResponse;
import ru.oksenyuk.anton.wallet.exception.InsufficientFundsException;
import ru.oksenyuk.anton.wallet.exception.WalletNotFoundException;
import ru.oksenyuk.anton.wallet.service.WalletService;

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

        try {
            WalletOperationResponse response = walletService.processOperation(request);
            return ResponseEntity.ok(response);

        } catch (WalletNotFoundException e) {
            log.warn("Wallet not found: {}", e.getWalletId());
            throw e;

        } catch (InsufficientFundsException e) {
            log.warn("Insufficient funds: {}", e.getMessage());
            throw e;
        }
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
