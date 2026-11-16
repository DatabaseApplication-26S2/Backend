package com.project1.enrollment.adapter.out.persistence.jpa;
import com.project1.enrollment.domain.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserAccountRepository extends JpaRepository<UserAccount, String> {}
