package com.rufit.workout;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ExerciseStatistics {
    private final NamedParameterJdbcTemplate jdbc;
    private final ExerciseSetResolver resolver;
    public ExerciseStatistics(NamedParameterJdbcTemplate jdbc,ExerciseSetResolver resolver) { this.jdbc=jdbc; this.resolver=resolver; }
    public View get(String owner,String exerciseName,UUID exerciseId) {
        if ((exerciseId==null && (exerciseName==null || exerciseName.isBlank() || exerciseName.trim().length()>80))
            || (exerciseId!=null && exerciseName!=null)) throw new IllegalArgumentException("운동 이름 또는 ID 중 하나를 전달해 주세요.");
        String name=exerciseId==null?exerciseName.trim():resolver.name(owner,exerciseId);
        String selection=exerciseId==null?"e.exercise_name = :name":"e.exercise_id = :id";
        var parameters=new HashMap<String,Object>(); parameters.put("owner",owner); parameters.put("name",name); parameters.put("id",exerciseId);
        return jdbc.queryForObject("""
            WITH recorded AS (
                SELECT w.id AS workout_id, w.finished_at, e.entry_order, e.weight, e.reps
                FROM workouts w JOIN workout_entries e ON e.workout_id=w.id
                JOIN completed_workout_sets c ON c.workout_id=w.id AND c.set_index=e.entry_order
                WHERE w.owner_name=:owner AND w.finished_at IS NOT NULL AND
            """+selection+"""
            ), totals AS (
                SELECT count(DISTINCT workout_id) AS workouts, count(*) AS sets,
                       coalesce(sum(reps),0) AS total_reps, coalesce(sum(weight*reps),0) AS volume,
                       max(finished_at) AS last_performed_at FROM recorded
            )
            SELECT t.*, best.workout_id, best.finished_at, best.entry_order, best.weight, best.reps
            FROM totals t LEFT JOIN LATERAL (
                SELECT * FROM recorded ORDER BY weight DESC, reps DESC, finished_at DESC, workout_id DESC, entry_order ASC LIMIT 1
            ) best ON true
            """,parameters,(row,index) -> {
                UUID workoutId=row.getObject("workout_id",UUID.class);
                var last=row.getTimestamp("last_performed_at");
                BestSet best=workoutId==null?null:new BestSet(workoutId,row.getTimestamp("finished_at").toInstant(),row.getInt("entry_order"),row.getBigDecimal("weight"),row.getInt("reps"));
                return new View(exerciseId,name,row.getLong("workouts"),row.getLong("sets"),row.getLong("total_reps"),row.getBigDecimal("volume"),last==null?null:last.toInstant(),best);
            });
    }
    public record BestSet(UUID workoutId,Instant finishedAt,int setIndex,BigDecimal weight,int reps) {}
    public record View(@Schema(nullable=true) UUID exerciseId,String exerciseName,long workouts,long completedSets,long totalReps,
        BigDecimal volumeKgReps,@Schema(nullable=true) Instant lastPerformedAt,@Schema(nullable=true) BestSet heaviestSet) {}
}
