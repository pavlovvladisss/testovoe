package ru.oksenyuk.anton.wallet.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.oksenyuk.anton.wallet.entity.WalletOperation;

@Repository
public interface WalletOperationRepository extends JpaRepository<WalletOperation, Long> {
}
