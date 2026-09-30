package ru.gdebenz.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BotUserRepository extends JpaRepository<BotUser, Long> {

    boolean existsByRole(Role role);

    List<BotUser> findByRole(Role role);

    List<BotUser> findByStatus(RegistrationStatus status);
}
