package com.rufit.routine;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
interface RoutineRepository extends JpaRepository<Routine, UUID> {
    Optional<Routine> findByIdAndOwnerName(UUID id, String owner);
    Page<Routine> findByOwnerName(String owner, Pageable pageable);
    @org.springframework.data.jpa.repository.Query("""
        select r from Routine r where r.ownerName=:owner and lower(r.title) like :pattern escape '!'
        """)
    Page<Routine> search(@org.springframework.data.repository.query.Param("owner") String owner,
        @org.springframework.data.repository.query.Param("pattern") String pattern,Pageable pageable);
}
