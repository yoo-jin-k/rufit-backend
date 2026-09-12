package com.rufit.workout;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
interface WorkoutRepository extends JpaRepository<Workout, UUID> {
    Page<Workout> findByOwnerNameAndFinishedAtIsNotNull(String owner, Pageable pageable);
    Optional<Workout> findByIdAndOwnerName(UUID id, String ownerName);
    Optional<Workout> findByOwnerNameAndFinishedAtIsNull(String ownerName);
}
