package com.example.testovoe.wallet.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.testovoe.wallet.entity.WalletOperation;

import java.util.List;
import java.util.UUID;

@Repository
public interface WalletOperationRepository extends JpaRepository<WalletOperation, Long> {
}
