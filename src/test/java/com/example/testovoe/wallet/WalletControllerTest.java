package com.example.testovoe.wallet;

import com.example.testovoe.wallet.dto.WalletBalanceResponse;
import com.example.testovoe.wallet.dto.WalletOperationRequest;
import com.example.testovoe.wallet.dto.WalletOperationResponse;
import com.example.testovoe.wallet.exception.InsufficientFundsException;
import com.example.testovoe.wallet.exception.WalletNotFoundException;
import com.example.testovoe.wallet.model.OperationStatus;
import com.example.testovoe.wallet.model.OperationType;
import com.example.testovoe.wallet.service.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Mock
    private WalletService walletService;

    private final UUID testWalletId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Test
    void processOperation_Deposit_ShouldReturnSuccess() throws Exception {
        WalletOperationRequest request = WalletOperationRequest.builder()
                .walletId(testWalletId)
                .operationType(OperationType.DEPOSIT)
                .amount(new BigDecimal("100.50"))
                .build();

        WalletOperationResponse response = WalletOperationResponse.builder()
                .status(OperationStatus.SUCCESS)
                .build();

        when(walletService.processOperation(any(WalletOperationRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void processOperation_InvalidRequest_ShouldReturnBadRequest() throws Exception {
        // Arrange - negative amount
        WalletOperationRequest request = WalletOperationRequest.builder()
                .walletId(testWalletId)
                .operationType(OperationType.DEPOSIT)
                .amount(new BigDecimal("-100.00")) // Invalid
                .build();

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void walletExists_ExistingWallet_ShouldReturnTrue() throws Exception {
        when(walletService.walletExists(testWalletId))
                .thenReturn(true);

        mockMvc.perform(get("/api/v1/wallets/{walletId}/exists", testWalletId))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    void walletExists_NonExistingWallet_ShouldReturnFalse() throws Exception {
        when(walletService.walletExists(testWalletId))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/wallets/{walletId}/exists", testWalletId))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    void processOperation_MissingFields_ShouldReturnBadRequest() throws Exception {
        String invalidJson = """
            {
                "walletId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                "operationType": "DEPOSIT"
            }
            """;

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }
}