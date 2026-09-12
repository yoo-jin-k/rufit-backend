package com.rufit.workout;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
interface WorkoutRepository extends JpaRepository<Workout, UUID> {
    Optional<Workout> findByIdAndOwnerName(UUID id, String ownerName);
    Optional<Workout> findByOwnerNameAndFinishedAtIsNull(String ownerName);
}
