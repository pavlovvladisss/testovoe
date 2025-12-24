package ru.oksenyuk.anton.wallet.exception;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
public class WalletNotFoundException extends RuntimeException {
    private final UUID walletId;

    public WalletNotFoundException(UUID walletId) {
        super("Wallet not found: " + walletId);
        this.walletId = walletId;
    }
}
