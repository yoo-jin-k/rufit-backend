package com.rufit.workout;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
interface WorkoutRepository extends JpaRepository<Workout, UUID> {
    @org.springframework.data.jpa.repository.Query("""
        select w from Workout w where w.ownerName = :owner and w.finishedAt is not null
        and exists (select e from Workout candidate join candidate.entries e
                    where candidate.id = w.id and e.exerciseName = :exerciseName)
        """)
    Page<Workout> findExerciseHistory(@org.springframework.data.repository.query.Param("owner") String owner,
        @org.springframework.data.repository.query.Param("exerciseName") String exerciseName, Pageable pageable);
    Page<Workout> findByOwnerNameAndFinishedAtIsNotNull(String owner, Pageable pageable);
    Optional<Workout> findByIdAndOwnerName(UUID id, String ownerName);
    Optional<Workout> findByOwnerNameAndFinishedAtIsNull(String ownerName);
}
