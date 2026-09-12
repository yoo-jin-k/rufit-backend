package com.rufit.workout;

import com.rufit.exercise.ExerciseService;
import com.rufit.exercise.ExerciseController;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ExerciseSetResolver {
    private final ExerciseService catalog;
    public ExerciseSetResolver(ExerciseService catalog) { this.catalog=catalog; }
    public List<Workout.Entry> resolve(String owner,List<WorkoutController.SetInput> inputs,List<Workout.Entry> existing) {
        Map<UUID,Workout.Entry> snapshots=new HashMap<>();
        for (var entry:existing) if (entry.exerciseId()!=null) snapshots.putIfAbsent(entry.exerciseId(),entry);
        Map<UUID,ExerciseController.View> lookedUp=new HashMap<>();
        return inputs.stream().map(input -> {
            if (input.exerciseId()==null) return new Workout.Entry(input.exerciseName(),input.weight(),input.reps());
            Workout.Entry snapshot=snapshots.get(input.exerciseId());
            String name;
            if (snapshot!=null) name=snapshot.name();
            else {
                var selected=lookedUp.computeIfAbsent(input.exerciseId(),id -> catalog.get(id,owner));
                if (selected.archivedAt()!=null) throw new IllegalArgumentException("보관한 종목은 새로 추가할 수 없습니다.");
                name=selected.name();
            }
            return new Workout.Entry(input.exerciseId(),name,input.weight(),input.reps());
        }).toList();
    }
    public String name(String owner,UUID id) { return catalog.get(id,owner).name(); }
}
