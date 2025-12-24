package ru.oksenyuk.anton.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.oksenyuk.anton.wallet.entity.OperationStatus;
import ru.oksenyuk.anton.wallet.entity.OperationType;

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
