package ru.gdebenz.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteStationRepository extends JpaRepository<FavoriteStation, Long> {

    List<FavoriteStation> findByUserIdOrderByCreatedAtAsc(long userId);

    Optional<FavoriteStation> findByUserIdAndOsmId(long userId, String osmId);

    long countByUserId(long userId);
}
