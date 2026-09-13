package com.rufit.workout;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PreviousExerciseSets {
    private final NamedParameterJdbcTemplate jdbc;
    PreviousExerciseSets(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    public PreviousExerciseSetsController.Results get(String owner,List<UUID> requested) {
        if(requested==null || requested.isEmpty() || requested.size()>50 || requested.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("종목 ID는 1~50개까지 조회할 수 있습니다.");
        List<UUID> ids=new ArrayList<>(new LinkedHashSet<>(requested));
        Map<String,Object> parameters=Map.of("owner",owner,"ids",ids);
        Map<UUID,String> names=new HashMap<>();
        jdbc.query("""
            select id,name from exercises where id in (:ids)
            and (owner_name=:owner or owner_name is null)
            """,parameters,rs -> { names.put(rs.getObject("id",UUID.class),rs.getString("name")); });
        if(names.size()!=ids.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"종목을 찾을 수 없습니다.");
        Map<UUID,PreviousExerciseSetsController.Record> records=new HashMap<>();
        Map<UUID,List<WorkoutController.SetInput>> sets=new HashMap<>();
        jdbc.query("""
            with latest as (
                select distinct on (e.exercise_id) e.exercise_id,w.id,w.finished_at
                from workout_entries e join workouts w on w.id=e.workout_id
                where w.owner_name=:owner and w.finished_at is not null and e.exercise_id in (:ids)
                order by e.exercise_id,w.finished_at desc,w.id desc
            )
            select l.exercise_id,l.id,l.finished_at,e.exercise_name,e.weight,e.reps
            from latest l join workout_entries e on e.workout_id=l.id and e.exercise_id=l.exercise_id
            order by l.exercise_id,e.entry_order
            """,parameters,rs -> {
                UUID exerciseId=rs.getObject("exercise_id",UUID.class);
                List<WorkoutController.SetInput> entries=sets.computeIfAbsent(exerciseId,id -> new ArrayList<>());
                entries.add(new WorkoutController.SetInput(rs.getString("exercise_name"),rs.getBigDecimal("weight"),rs.getInt("reps"),exerciseId));
                records.putIfAbsent(exerciseId,new PreviousExerciseSetsController.Record(rs.getObject("id",UUID.class),rs.getTimestamp("finished_at").toInstant(),entries));
            });
        return new PreviousExerciseSetsController.Results(ids.stream().map(id -> {
            var record=records.get(id);
            var snapshot=record==null?null:new PreviousExerciseSetsController.Record(record.workoutId(),record.finishedAt(),List.copyOf(record.sets()));
            return new PreviousExerciseSetsController.Item(id,names.get(id),snapshot);
        }).toList());
    }
}
