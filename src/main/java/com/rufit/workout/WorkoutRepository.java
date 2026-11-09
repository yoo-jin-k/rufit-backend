package com.rufit.workout;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface WorkoutRepository extends JpaRepository<Workout, UUID> {
  @org.springframework.data.jpa.repository.Query(
    """
    select w from Workout w where w.ownerName = :owner and w.finishedAt is not null
    and exists (select e from Workout candidate join candidate.entries e
                where candidate.id = w.id and e.exerciseName = :exerciseName)
    """
  )
  Page<Workout> findExerciseHistory(
    @org.springframework.data.repository.query.Param("owner") String owner,
    @org.springframework.data.repository.query.Param("exerciseName") String exerciseName,
    Pageable pageable
  );

  @org.springframework.data.jpa.repository.Query(
    """
    select w from Workout w where w.ownerName=:owner and w.finishedAt is not null
    and exists (select e from Workout candidate join candidate.entries e where candidate.id=w.id and e.exerciseId=:exerciseId)
    """
  )
  Page<Workout> findExerciseHistoryById(
    @org.springframework.data.repository.query.Param("owner") String owner,
    @org.springframework.data.repository.query.Param("exerciseId") UUID exerciseId,
    Pageable pageable
  );

  @org.springframework.data.jpa.repository.Query(
    """
    select w from Workout w where w.ownerName=:owner and w.finishedAt is not null
    and ((w.recordDate is null and w.startedAt>=:from and w.startedAt<:until) or w.recordDate=:date)
    """
  )
  Page<Workout> findDailyHistory(
    @org.springframework.data.repository.query.Param("owner") String owner,
    @org.springframework.data.repository.query.Param("from") java.time.Instant from,
    @org.springframework.data.repository.query.Param("until") java.time.Instant until,
    @org.springframework.data.repository.query.Param("date") java.time.LocalDate date,
    Pageable pageable
  );

  Page<Workout> findByOwnerNameAndFinishedAtIsNotNull(String owner, Pageable pageable);
  Optional<Workout> findByIdAndOwnerName(UUID id, String ownerName);
  Optional<Workout> findByOwnerNameAndFinishedAtIsNullAndCancelledAtIsNull(String ownerName);
}
