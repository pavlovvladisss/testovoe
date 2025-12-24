package com.example.testovoe.wallet.service;


import com.example.testovoe.wallet.dto.WalletBalanceResponse;
import com.example.testovoe.wallet.dto.WalletOperationRequest;
import com.example.testovoe.wallet.dto.WalletOperationResponse;
import com.example.testovoe.wallet.entity.Wallet;
import com.example.testovoe.wallet.entity.WalletOperation;
import com.example.testovoe.wallet.exception.InsufficientFundsException;
import com.example.testovoe.wallet.model.OperationStatus;
import com.example.testovoe.wallet.model.OperationType;
import com.example.testovoe.wallet.repository.WalletOperationRepository;
import com.example.testovoe.wallet.repository.WalletRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {
    @Value("${wallet.cache.operations-sync-interval-ms}")
    private String operationsSyncIntervalMs;



    private final WalletRepository walletRepository;
    private final WalletOperationRepository operationRepository;


    // In-memory кэш: UUID -> AtomicReference<BigDecimal>
    private final ConcurrentHashMap<UUID, AtomicReference<BigDecimal>> balanceCache =
            new ConcurrentHashMap<>();

    // Очередь для асинхронной записи операций
    private final ConcurrentLinkedQueue<WalletOperation> operationQueue =
            new ConcurrentLinkedQueue<>();

    // Очередь для синхронизации балансов
    private final ConcurrentHashMap<UUID, BigDecimal> balanceSyncQueue =
            new ConcurrentHashMap<>();


    /**
     * Основная операция - работает полностью в памяти
     */
    public WalletOperationResponse processOperation(WalletOperationRequest request) {
        UUID walletId = request.getWalletId();
        OperationType operationType = request.getOperationType();
        BigDecimal amount = request.getAmount();

        // 1. Получаем или создаем AtomicReference для кошелька
        AtomicReference<BigDecimal> balanceRef = balanceCache.computeIfAbsent(
                walletId,
                id -> new AtomicReference<>(loadBalanceFromDb(id))
        );

        // 2. Атомарно обновляем баланс в памяти
        boolean success = updateBalanceInMemory(balanceRef, operationType, amount);

        if (!success) {
            throw new InsufficientFundsException(walletId,
                    balanceRef.get(), amount);
        }

        // 3. Добавляем операцию в очередь для асинхронной записи
        WalletOperation operation = WalletOperation.builder()
                .walletId(walletId)
                .operationType(operationType)
                .amount(amount)
                .status(OperationStatus.SUCCESS)
                .build();
        operationQueue.offer(operation);

        // 4. Помечаем кошелек для синхронизации баланса
        balanceSyncQueue.put(walletId, balanceRef.get());

        // 5. Возвращаем ответ мгновенно
        return WalletOperationResponse.builder()
                .status(OperationStatus.SUCCESS)
                .build();
    }

    /**
     * Атомарное обновление баланса в памяти
     */
    private boolean updateBalanceInMemory(AtomicReference<BigDecimal> balanceRef,
                                          OperationType operationType,
                                          BigDecimal amount) {
        BigDecimal current;
        BigDecimal newValue;

        do {
            current = balanceRef.get();

            if (operationType == OperationType.DEPOSIT) {
                newValue = current.add(amount);
            } else {
                // WITHDRAW
                if (current.compareTo(amount) < 0) {
                    return false; // Недостаточно средств
                }
                newValue = current.subtract(amount);
            }

        } while (!balanceRef.compareAndSet(current, newValue));

        return true;
    }

    /**
     * Загрузка баланса из БД при первом обращении
     */
    private BigDecimal loadBalanceFromDb(UUID walletId) {
        return walletRepository.findById(walletId)
                .map(Wallet::getBalance)
                .orElseGet(() -> {
                    // Создаем новый кошелек в БД
                    Wallet wallet = Wallet.builder()
                            .id(walletId)
                            .balance(BigDecimal.ZERO)
                            .build();
                    walletRepository.save(wallet);
                    return BigDecimal.ZERO;
                });
    }

    /**
     * Получение баланса (читаем из кэша)
     */
    public WalletBalanceResponse getWalletBalance(UUID walletId) {
        AtomicReference<BigDecimal> balanceRef = balanceCache.get(walletId);

        if (balanceRef != null) {
            // Есть в кэше
            return new WalletBalanceResponse(walletId, balanceRef.get());
        } else {
            // Нет в кэше, грузим из БД
            BigDecimal balance = loadBalanceFromDb(walletId);
            balanceCache.put(walletId, new AtomicReference<>(balance));
            return new WalletBalanceResponse(walletId, balance);
        }
    }


    /**
     * Пакетная синхронизация балансов с БД
     */
    @Scheduled(fixedDelayString = "${wallet.cache.sync-interval-ms}")
    @Transactional
    public void syncBalancesToDatabase() {
        if (balanceSyncQueue.isEmpty()) {
            return;
        }

        Map<UUID, BigDecimal> batch = new HashMap<>();

        // Собираем батч для обновления (максимум 1000 записей)
        int count = 0;
        Iterator<Map.Entry<UUID, BigDecimal>> iterator = balanceSyncQueue.entrySet().iterator();

        while (iterator.hasNext() && count < 1000) {
            Map.Entry<UUID, BigDecimal> entry = iterator.next();
            batch.put(entry.getKey(), entry.getValue());
            iterator.remove();
            count++;
        }

        if (!batch.isEmpty()) {
            log.debug("Syncing {} wallet balances to DB", batch.size());

            // Конвертируем Map в строку
            String updatesStr = batch.entrySet().stream()
                    .map(e -> e.getKey() + "," + e.getValue())
                    .collect(Collectors.joining("|"));

            walletRepository.updateBalancesBatch(updatesStr);
        }
    }

    /**
     * Асинхронное сохранение операций в БД
     */
    @Scheduled(fixedDelayString = "${wallet.cache.operations-sync-interval-ms}")
    public void saveOperationsToDatabase() {
        if (operationQueue.isEmpty()) {
            return;
        }

        List<WalletOperation> batch = new ArrayList<>();

        // Максимальное время выполнения: 500ms (половина от интервала)
        long startTime = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(500);
        int maxOperations = 500;

        // Собираем батч операций с проверкой времени
        int count = 0;
        while (!operationQueue.isEmpty() &&
                count < maxOperations &&
                (System.nanoTime() - startTime) < timeoutNanos) {

            WalletOperation operation = operationQueue.poll();
            if (operation != null) {
                batch.add(operation);
                count++;
            }
        }

        if (!batch.isEmpty()) {
            log.debug("Saving {} operations to DB", batch.size());
            operationRepository.saveAll(batch);
        }
    }

    /**
     * Сброс кэша (для тестов)
     */
    @PreDestroy
    public void shutdown() {
        // При остановке приложения синхронизируем всё
        log.info("Shutting down cache, syncing remaining balances...");
        syncAllBalances();
    }

    private void syncAllBalances() {
        for (Map.Entry<UUID, AtomicReference<BigDecimal>> entry : balanceCache.entrySet()) {
            walletRepository.updateBalance(entry.getKey(), entry.getValue().get());
        }
    }

    @Transactional(readOnly = true)
    public boolean walletExists(UUID walletId) {
        return walletRepository.existsById(walletId);
    }
}
