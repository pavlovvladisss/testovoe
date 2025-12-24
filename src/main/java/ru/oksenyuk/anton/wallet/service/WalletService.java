package ru.oksenyuk.anton.wallet.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.oksenyuk.anton.wallet.dto.WalletBalanceResponse;
import ru.oksenyuk.anton.wallet.dto.WalletOperationRequest;
import ru.oksenyuk.anton.wallet.dto.WalletOperationResponse;
import ru.oksenyuk.anton.wallet.entity.OperationStatus;
import ru.oksenyuk.anton.wallet.entity.OperationType;
import ru.oksenyuk.anton.wallet.entity.Wallet;
import ru.oksenyuk.anton.wallet.entity.WalletOperation;
import ru.oksenyuk.anton.wallet.exception.InsufficientFundsException;
import ru.oksenyuk.anton.wallet.exception.WalletNotFoundException;
import ru.oksenyuk.anton.wallet.repository.WalletOperationRepository;
import ru.oksenyuk.anton.wallet.repository.WalletRepository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletOperationRepository operationRepository;
    private final Semaphore operationSemaphore;

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public WalletOperationResponse processOperation(WalletOperationRequest request) {
        try {
            operationSemaphore.acquire();
            return processWithRetry(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Operation interrupted for wallet: {}", request.getWalletId(), e);
            throw new RuntimeException("Operation interrupted", e);
        } finally {
            operationSemaphore.release();
        }
    }

    private WalletOperationResponse processWithRetry(WalletOperationRequest request) {
        int maxAttempts = 3;
        long initialDelay = 100; // ms

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return processOperationInternal(request);
            } catch (OptimisticLockingFailureException e) {
                if (attempt == maxAttempts) {
                    log.error("Max retry attempts reached for wallet {}", request.getWalletId());
                    throw new RuntimeException("Failed after " + maxAttempts + " attempts", e);
                }

                long delay = initialDelay * (1L << (attempt - 1)); // exponential backoff
                log.debug("Retry attempt {} for wallet {}, waiting {} ms",
                        attempt, request.getWalletId(), delay);

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }
        throw new RuntimeException("Failed to process operation");
    }

    private WalletOperationResponse processOperationInternal(WalletOperationRequest request) {
        UUID walletId = request.getWalletId();
        OperationType operationType = request.getOperationType();
        BigDecimal amount = request.getAmount();

        log.debug("Processing {} operation for wallet {}: {}", operationType, walletId, amount);

        try {
            // Пытаемся найти кошелек с блокировкой
            Optional<Wallet> walletOpt = walletRepository.findByIdForUpdate(walletId);

            Wallet wallet;
            if (walletOpt.isPresent()) {
                wallet = walletOpt.get();
            } else {
                // Создаем новый кошелек если не найден
                log.info("Creating new wallet: {}", walletId);
                wallet = Wallet.builder()
                        .id(walletId)
                        .balance(BigDecimal.ZERO)
                        .version(0L)
                        .build();
                // Сохраняем новый кошелек
                wallet = walletRepository.save(wallet);
                // Получаем его снова с блокировкой (чтобы он был в текущей транзакции)
                wallet = walletRepository.findByIdForUpdate(walletId)
                        .orElseThrow(() -> new RuntimeException("Failed to create wallet"));
            }

            BigDecimal newBalance = calculateNewBalance(wallet, operationType, amount);
            wallet.setBalance(newBalance);
            walletRepository.save(wallet);

            // Теперь сохраняем запись об операции (кошелек уже существует)
            saveOperationRecord(request, OperationStatus.SUCCESS, null);

            log.info("Operation completed successfully for wallet {}: {} {}",
                    walletId, operationType, amount);

            return WalletOperationResponse.builder()
                    .walletId(walletId)
                    .operationType(operationType)
                    .amount(amount)
                    .status(OperationStatus.SUCCESS)
                    .message("Operation completed successfully")
                    .build();

        } catch (InsufficientFundsException e) {
            log.warn("Operation failed for wallet {}: {}", walletId, e.getMessage());
            saveOperationRecord(request, OperationStatus.FAILED, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during operation for wallet {}", walletId, e);
            saveOperationRecord(request, OperationStatus.FAILED, "Internal server error");
            throw new RuntimeException("Failed to process operation", e);
        }
    }

    private BigDecimal calculateNewBalance(Wallet wallet, OperationType operationType, BigDecimal amount) {
        if (operationType == OperationType.DEPOSIT) {
            return wallet.getBalance().add(amount);
        } else {
            if (wallet.getBalance().compareTo(amount) < 0) {
                throw new InsufficientFundsException(wallet.getId(), wallet.getBalance(), amount);
            }
            return wallet.getBalance().subtract(amount);
        }
    }

    private void saveOperationRecord(WalletOperationRequest request, OperationStatus status, String errorMessage) {
        WalletOperation operation = WalletOperation.builder()
                .walletId(request.getWalletId())
                .operationType(request.getOperationType())
                .amount(request.getAmount())
                .status(status)
                .errorMessage(errorMessage)
                .build();
        operationRepository.save(operation);
    }

    private void updateOperationRecord(WalletOperationRequest request, OperationStatus status, String errorMessage) {
        // Находим последнюю операцию для этого кошелька и обновляем ее
        // Или создаем новую запись об операции
        saveOperationRecord(request, status, errorMessage);
    }

    @Async
    public CompletableFuture<WalletOperationResponse> processOperationAsync(WalletOperationRequest request) {
        return CompletableFuture.completedFuture(processOperation(request));
    }

    @Transactional(readOnly = true)
    public WalletBalanceResponse getWalletBalance(UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
        return new WalletBalanceResponse(walletId, wallet.getBalance());
    }

    public boolean walletExists(UUID walletId) {
        return walletRepository.existsById(walletId);
    }
}
