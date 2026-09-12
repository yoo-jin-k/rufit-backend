package com.rufit.exercise;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

interface ExerciseRepository extends JpaRepository<Exercise, UUID> {
    Optional<Exercise> findByIdAndOwnerName(UUID id, String owner);
    @Query("""
        select e from Exercise e where (:includeArchived=true or e.archivedAt is null) and (e.ownerName is null or e.ownerName=:owner)
        and lower(e.name) like :pattern escape '!'
        and (:part is null or e.bodyPart=:part) and (:equipment is null or e.equipment=:equipment)
        """)
    Page<Exercise> search(@Param("owner") String owner, @Param("pattern") String pattern,
        @Param("includeArchived") boolean includeArchived, @Param("part") Exercise.BodyPart part, @Param("equipment") Exercise.Equipment equipment, Pageable pageable);
}
