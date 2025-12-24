package ru.oksenyuk.anton.wallet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.oksenyuk.anton.wallet.dto.WalletOperationRequest;
import ru.oksenyuk.anton.wallet.dto.WalletOperationResponse;
import ru.oksenyuk.anton.wallet.entity.OperationType;
import ru.oksenyuk.anton.wallet.entity.Wallet;
import ru.oksenyuk.anton.wallet.entity.WalletOperation;
import ru.oksenyuk.anton.wallet.exception.InsufficientFundsException;
import ru.oksenyuk.anton.wallet.exception.WalletNotFoundException;
import ru.oksenyuk.anton.wallet.repository.WalletOperationRepository;
import ru.oksenyuk.anton.wallet.repository.WalletRepository;
import ru.oksenyuk.anton.wallet.service.WalletService;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static ru.oksenyuk.anton.wallet.entity.OperationStatus.FAILED;
import static ru.oksenyuk.anton.wallet.entity.OperationStatus.SUCCESS;

@ExtendWith(MockitoExtension.class)
class WalletApplicationTests {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletOperationRepository operationRepository;

    @Mock
    private Semaphore operationSemaphore;

    @InjectMocks
    private WalletService walletService;

    private UUID walletId;
    private Wallet wallet;
    private WalletOperationRequest depositRequest;
    private WalletOperationRequest withdrawRequest;

    @BeforeEach
    void setUp() {
        walletId = UUID.randomUUID();
        wallet = Wallet.builder()
                .id(walletId)
                .balance(new BigDecimal("1000.00"))
                .version(0L)
                .build();

        depositRequest = new WalletOperationRequest(
                walletId,
                OperationType.DEPOSIT,
                new BigDecimal("500.00")
        );

        withdrawRequest = new WalletOperationRequest(
                walletId,
                OperationType.WITHDRAW,
                new BigDecimal("300.00")
        );

        // Мокаем поведение семафора
        try {
            doNothing().when(operationSemaphore).acquire();
            doNothing().when(operationSemaphore).release();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testSuccessfulDeposit() throws Exception {
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenReturn(wallet);
        doNothing().when(operationSemaphore).acquire();

        WalletOperationResponse response = walletService.processOperation(depositRequest);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getOperationType()).isEqualTo(OperationType.DEPOSIT);
        assertThat(response.getAmount()).isEqualByComparingTo("500.00");

        verify(walletRepository).save(argThat(w ->
                w.getBalance().compareTo(new BigDecimal("1500.00")) == 0));
        verify(operationRepository).save(any(WalletOperation.class));
        verify(operationSemaphore).acquire();
        verify(operationSemaphore).release();
    }

    @Test
    void testSuccessfulWithdraw() throws Exception {
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenReturn(wallet);
        doNothing().when(operationSemaphore).acquire();

        WalletOperationResponse response = walletService.processOperation(withdrawRequest);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getOperationType()).isEqualTo(OperationType.WITHDRAW);
        assertThat(response.getAmount()).isEqualByComparingTo("300.00");

        verify(walletRepository).save(argThat(w ->
                w.getBalance().compareTo(new BigDecimal("700.00")) == 0));
        verify(operationRepository).save(any(WalletOperation.class));
        verify(operationSemaphore).acquire();
        verify(operationSemaphore).release();
    }

    @Test
    void testWithdrawWithInsufficientFunds() throws Exception {
        WalletOperationRequest largeWithdraw = new WalletOperationRequest(
                walletId,
                OperationType.WITHDRAW,
                new BigDecimal("2000.00")
        );

        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(wallet));
        doNothing().when(operationSemaphore).acquire();

        assertThatThrownBy(() -> walletService.processOperation(largeWithdraw))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient funds");

        verify(operationSemaphore).acquire();
        verify(operationSemaphore).release();
        verify(operationRepository).save(argThat(op ->
                op.getStatus() == FAILED));
    }

    @Test
    void testWalletNotFound() throws Exception {
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.empty());
        doNothing().when(operationSemaphore).acquire();

        assertThatThrownBy(() -> walletService.processOperation(depositRequest))
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessageContaining("Wallet not found");

        verify(operationSemaphore).acquire();
        verify(operationSemaphore).release();
        verify(operationRepository).save(argThat(op ->
                op.getStatus() == FAILED));
    }

    @Test
    void testOperationInterrupted() throws Exception {
        doThrow(new InterruptedException("Test interrupt")).when(operationSemaphore).acquire();

        assertThatThrownBy(() -> walletService.processOperation(depositRequest))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Operation interrupted");

        verify(operationSemaphore).acquire();
        verify(operationSemaphore, never()).release();
    }

    @Test
    void testOptimisticLockRetry() throws Exception {
        // Первый вызов бросает OptimisticLockingFailureException, второй успешен
        when(walletRepository.findByIdForUpdate(walletId))
                .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("Optimistic lock"))
                .thenReturn(Optional.of(wallet));
        when(walletRepository.save(any(Wallet.class))).thenReturn(wallet);
        doNothing().when(operationSemaphore).acquire();

        WalletOperationResponse response = walletService.processOperation(depositRequest);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);

        // Должно быть две попытки вызова findByIdForUpdate
        verify(walletRepository, times(2)).findByIdForUpdate(walletId);
        verify(operationSemaphore).acquire();
        verify(operationSemaphore).release();
    }
}
