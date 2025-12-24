package ru.oksenyuk.anton.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.oksenyuk.anton.wallet.dto.WalletOperationRequest;
import ru.oksenyuk.anton.wallet.entity.OperationType;
import ru.oksenyuk.anton.wallet.entity.Wallet;
import ru.oksenyuk.anton.wallet.repository.WalletRepository;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class WalletIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID existingWalletId;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        existingWalletId = UUID.randomUUID();
    }
    @Test
    void testDepositOperation() throws Exception {
        // Просто делаем один депозит - это создаст кошелек
        WalletOperationRequest request = new WalletOperationRequest(
                existingWalletId,
                OperationType.DEPOSIT,
                new BigDecimal("1000.00")
        );

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(existingWalletId.toString()))
                .andExpect(jsonPath("$.operationType").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value(1000.00))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void testGetBalanceForNonExistentWallet() throws Exception {
        UUID nonExistentWalletId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/wallets/{walletId}", nonExistentWalletId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Wallet Not Found"));
    }

    @Test
    void testWithdrawWithInsufficientFunds() throws Exception {
        // Кошелек уже создан в setUp() с балансом 0

        // Пытаемся снять средства без предварительного депозита
        WalletOperationRequest withdrawRequest = new WalletOperationRequest(
                existingWalletId,
                OperationType.WITHDRAW,
                new BigDecimal("1000.00")
        );

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withdrawRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Insufficient Funds"));
    }

    @Test
    void testInvalidJsonRequest() throws Exception {
        String invalidJson = "{ invalid json }";

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    void testValidationErrors() throws Exception {
        WalletOperationRequest invalidRequest = new WalletOperationRequest(
                null,  // null walletId
                null,  // null operationType
                new BigDecimal("-100")  // negative amount
        );

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }
}