package com.rufit.workout;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
class DraftOperations {
    private final JdbcTemplate jdbc;
    DraftOperations(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    java.util.OptionalLong appliedVersion(UUID workoutId,UUID operationId,String owner) {
        var versions=jdbc.queryForList("select d.applied_version from workout_draft_operations d join workouts w on w.id=d.workout_id where d.workout_id=? and d.operation_id=? and w.owner_name=?",Long.class,workoutId,operationId,owner);
        return versions.isEmpty()?java.util.OptionalLong.empty():java.util.OptionalLong.of(versions.getFirst());
    }
    void remember(UUID workoutId,UUID operationId,long version) {
        jdbc.update("insert into workout_draft_operations(workout_id,operation_id,applied_version) values(?,?,?)",workoutId,operationId,version);
    }
}
