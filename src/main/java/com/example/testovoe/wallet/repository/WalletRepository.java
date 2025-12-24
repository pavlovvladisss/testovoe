package com.example.testovoe.wallet.repository;

import com.example.testovoe.wallet.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {
    Optional<Wallet> findById(UUID uuid);

    // Пакетное обновление балансов
    @Modifying
    @Query("UPDATE Wallet w SET w.balance = :balance WHERE w.id = :walletId")
    void updateBalance(@Param("walletId") UUID walletId,
                       @Param("balance") BigDecimal balance);

    // Массовое обновление
    @Modifying
    @Transactional
    @Query(value =
            "UPDATE wallet w " +
                    "SET balance = CAST(split_part(upd, ',', 2) AS NUMERIC) " +
                    "FROM unnest(string_to_array(?#{#updates}, '|')) AS upd " +  // SpEL
                    "WHERE w.id = CAST(split_part(upd, ',', 1) AS UUID)",
            nativeQuery = true)
    void updateBalancesBatch(@Param("updates") String updates);

    boolean existsById(UUID id);
}
