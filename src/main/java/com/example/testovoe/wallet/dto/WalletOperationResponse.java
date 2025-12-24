package com.example.testovoe.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.example.testovoe.wallet.model.OperationStatus;
import com.example.testovoe.wallet.model.OperationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletOperationResponse {
    private UUID walletId;
    private OperationType operationType;
    private BigDecimal amount;
    private OperationStatus status;
    private String message;
    private LocalDateTime timestamp;
}
