package com.rufit.routine;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
interface RoutineRepository extends JpaRepository<Routine, UUID> {
    Optional<Routine> findByIdAndOwnerName(UUID id, String owner);
    Page<Routine> findByOwnerName(String owner, Pageable pageable);
}
