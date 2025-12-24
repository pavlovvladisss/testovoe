package ru.oksenyuk.anton.wallet.exception;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
public class InsufficientFundsException extends RuntimeException {
    private final UUID walletId;
    private final BigDecimal currentBalance;
    private final BigDecimal requestedAmount;

    public InsufficientFundsException(UUID walletId, BigDecimal currentBalance, BigDecimal requestedAmount) {
        super(String.format(
                "Insufficient funds in wallet %s. Current balance: %.2f, Requested amount: %.2f",
                walletId, currentBalance, requestedAmount
        ));
        this.walletId = walletId;
        this.currentBalance = currentBalance;
        this.requestedAmount = requestedAmount;
    }
}
