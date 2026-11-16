package com.crs.backend.domain.entity;

import com.crs.backend.domain.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ENT-01 사용자. 물리명: user_id, password_hash, user_name, role_cd
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

    @Id
    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_cd", length = 20, nullable = false)
    private UserRole role;

    @Builder
    public UserAccount(String userId, String passwordHash, String userName, UserRole role) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.userName = userName;
        this.role = role;
    }

    public boolean isStudent() {
        return role == UserRole.STUDENT;
    }
}
