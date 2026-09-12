package com.rufit.routine;

import com.rufit.workout.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.rufit.routine.RoutineController.*;

@Service
@Transactional
public class RoutineService {
    private final RoutineRepository repository;
    private final WorkoutService workouts;
    RoutineService(RoutineRepository repository, WorkoutService workouts) { this.repository=repository; this.workouts=workouts; }
    public View create(String owner, Create request) {
        return View.from(repository.saveAndFlush(new Routine(owner, request.title(), entries(request.sets()))));
    }
    @Transactional(readOnly=true)
    public View get(UUID id, String owner) { return View.from(owned(id, owner)); }
    @Transactional(readOnly=true)
    public List<View> list(String owner, int page) {
        if (page < 0 || page > 10000) throw new IllegalArgumentException("페이지 범위를 확인해 주세요.");
        return repository.findByOwnerName(owner, PageRequest.of(page, 20, Sort.by("id"))).map(View::from).toList();
    }
    public View update(UUID id, String owner, Edit request) {
        Routine r = owned(id, owner);
        if (r.version != request.version()) throw new WorkoutConflict("루틴이 변경되었습니다. 다시 조회해 주세요.");
        r.replace(request.title(), entries(request.sets()));
        return View.from(repository.saveAndFlush(r));
    }
    public WorkoutController.View start(UUID id, String owner) {
        Routine r = owned(id, owner);
        WorkoutController.View created = workouts.start(owner);
        return workouts.update(created.id(), owner, new WorkoutController.Edit(created.version(), View.from(r).sets()));
    }
    private Routine owned(UUID id, String owner) {
        return repository.findByIdAndOwnerName(id, owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "루틴을 찾을 수 없습니다."));
    }
    private List<Workout.Entry> entries(List<WorkoutController.SetInput> sets) {
        return sets.stream().map(s -> new Workout.Entry(s.exerciseName(), s.weight(), s.reps())).toList();
    }
}
