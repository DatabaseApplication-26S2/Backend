package com.crs.backend.adapter.out.persistence.jpa;
import com.crs.backend.domain.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserAccountRepository extends JpaRepository<UserAccount, String> {}
