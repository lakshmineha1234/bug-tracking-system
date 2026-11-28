package com.bugtracker.repository;

import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByRole(Role role);

    boolean existsByRoleAndEnabledTrue(Role role);
}
