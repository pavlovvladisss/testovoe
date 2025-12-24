package com.example.testovoe.wallet;

import com.example.testovoe.wallet.dto.WalletBalanceResponse;
import com.example.testovoe.wallet.dto.WalletOperationRequest;
import com.example.testovoe.wallet.entity.Wallet;
import com.example.testovoe.wallet.entity.WalletOperation;
import com.example.testovoe.wallet.exception.InsufficientFundsException;
import com.example.testovoe.wallet.model.OperationStatus;
import com.example.testovoe.wallet.model.OperationType;
import com.example.testovoe.wallet.repository.WalletOperationRepository;
import com.example.testovoe.wallet.repository.WalletRepository;
import com.example.testovoe.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest
class WalletServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17"))
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletOperationRepository operationRepository;

    private UUID testWalletId;

    @BeforeEach
    void setUp() {
        testWalletId = UUID.randomUUID();
        operationRepository.deleteAll();
        walletRepository.deleteAll();
    }

    @Test
    void processOperation_Deposit_ShouldCreateWalletAndUpdateBalance() {
        // Arrange
        WalletOperationRequest request = WalletOperationRequest.builder()
                .walletId(testWalletId)
                .operationType(OperationType.DEPOSIT)
                .amount(new BigDecimal("100.50"))
                .build();

        // Act
        var response = walletService.processOperation(request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(OperationStatus.SUCCESS);

        // Check cache immediately
        WalletBalanceResponse balanceResponse = walletService.getWalletBalance(testWalletId);
        assertThat(balanceResponse.getBalance()).isEqualTo(new BigDecimal("100.50"));

        // Wait for async sync and verify database
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // Check database
                    Optional<Wallet> wallet = walletRepository.findById(testWalletId);
                    assertThat(wallet).isPresent();
                    assertThat(wallet.get().getBalance()).isEqualByComparingTo("100.50");

                    // Check operations were saved
                    assertThat(operationRepository.count()).isGreaterThan(0);
                });
    }

    @Test
    void processOperation_WithdrawWithSufficientFunds_ShouldUpdateBalance() {
        // Arrange - first deposit
        WalletOperationRequest depositRequest = WalletOperationRequest.builder()
                .walletId(testWalletId)
                .operationType(OperationType.DEPOSIT)
                .amount(new BigDecimal("200.00"))
                .build();
        walletService.processOperation(depositRequest);

        // Wait for sync
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> walletRepository.existsById(testWalletId));

        // Act - then withdraw
        WalletOperationRequest withdrawRequest = WalletOperationRequest.builder()
                .walletId(testWalletId)
                .operationType(OperationType.WITHDRAW)
                .amount(new BigDecimal("50.75"))
                .build();
        var response = walletService.processOperation(withdrawRequest);

        // Assert
        assertThat(response.getStatus()).isEqualTo(OperationStatus.SUCCESS);

        // Check balance in cache
        WalletBalanceResponse balanceResponse = walletService.getWalletBalance(testWalletId);
        assertThat(balanceResponse.getBalance()).isEqualByComparingTo("149.25");
    }

    @Test
    void processOperation_WithdrawWithInsufficientFunds_ShouldThrowException() {
        // Arrange
        WalletOperationRequest depositRequest = WalletOperationRequest.builder()
                .walletId(testWalletId)
                .operationType(OperationType.DEPOSIT)
                .amount(new BigDecimal("100.00"))
                .build();
        walletService.processOperation(depositRequest);

        // Wait for wallet creation
        await()
                .atMost(10, TimeUnit.SECONDS)
                .until(() -> walletRepository.existsById(testWalletId));

        WalletOperationRequest withdrawRequest = WalletOperationRequest.builder()
                .walletId(testWalletId)
                .operationType(OperationType.WITHDRAW)
                .amount(new BigDecimal("200.00"))
                .build();

        // Act & Assert
        assertThatThrownBy(() -> walletService.processOperation(withdrawRequest))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void getWalletBalance_NonExistingWallet_ShouldCreateWallet() {
        // Act
        WalletBalanceResponse response = walletService.getWalletBalance(testWalletId);

        // Assert
        assertThat(response.getWalletId()).isEqualTo(testWalletId);
        assertThat(response.getBalance()).isEqualByComparingTo("0");

        // Wallet should be created in DB after sync
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> walletRepository.existsById(testWalletId));

        assertThat(walletRepository.existsById(testWalletId)).isTrue();
    }

    @Test
    void syncBalancesToDatabase_ShouldUpdateMultipleWallets() {
        // Arrange - create multiple wallets
        UUID wallet1 = UUID.randomUUID();
        UUID wallet2 = UUID.randomUUID();

        walletService.processOperation(WalletOperationRequest.builder()
                .walletId(wallet1)
                .operationType(OperationType.DEPOSIT)
                .amount(new BigDecimal("100.00"))
                .build());

        walletService.processOperation(WalletOperationRequest.builder()
                .walletId(wallet2)
                .operationType(OperationType.DEPOSIT)
                .amount(new BigDecimal("200.00"))
                .build());

        // Act & Assert - wait for sync
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(1000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Optional<Wallet> dbWallet1 = walletRepository.findById(wallet1);
                    Optional<Wallet> dbWallet2 = walletRepository.findById(wallet2);

                    assertThat(dbWallet1).isPresent();
                    assertThat(dbWallet1.get().getBalance())
                            .isEqualByComparingTo("100.00");

                    assertThat(dbWallet2).isPresent();
                    assertThat(dbWallet2.get().getBalance())
                            .isEqualByComparingTo("200.00");
                });
    }

    @Test
    void saveOperationsToDatabase_ShouldSaveBatch() {
        // Arrange - queue multiple operations
        int operationCount = 10;
        for (int i = 0; i < operationCount; i++) {
            WalletOperationRequest request = WalletOperationRequest.builder()
                    .walletId(UUID.randomUUID())
                    .operationType(OperationType.DEPOSIT)
                    .amount(new BigDecimal("10.00"))
                    .build();
            walletService.processOperation(request);
        }

        // Act & Assert - wait for operations to be saved
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(operationRepository.count()).isEqualTo(operationCount);

                    // All operations should have SUCCESS status
                    Iterable<WalletOperation> operations = operationRepository.findAll();
                    for (WalletOperation op : operations) {
                        assertThat(op.getStatus()).isEqualTo(OperationStatus.SUCCESS);
                    }
                });
    }

    @Test
    void walletExists_ShouldReturnCorrectStatus() {
        // Arrange - wallet doesn't exist yet
        assertThat(walletService.walletExists(testWalletId)).isFalse();

        // Act - create wallet by checking balance
        walletService.getWalletBalance(testWalletId);

        // Assert - wait for wallet to be created
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> walletService.walletExists(testWalletId));

        assertThat(walletService.walletExists(testWalletId)).isTrue();
    }
}
