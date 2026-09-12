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
    private final RetiredRoutineKeys retired;
    RoutineService(RoutineRepository repository, WorkoutService workouts, RetiredRoutineKeys retired) { this.repository=repository; this.workouts=workouts; this.retired=retired; }
    public View create(String owner, Create request, UUID requestId) {
        var replay = repository.findByIdAndOwnerName(requestId, owner);
        if (replay.isPresent()) return View.from(replay.get());
        if (retired.belongsTo(requestId, owner)) throw new ResponseStatusException(HttpStatus.GONE, "삭제한 루틴입니다.");
        if (retired.exists(requestId) || repository.existsById(requestId)) throw new WorkoutConflict("요청 식별자를 다시 생성해 주세요.");
        return View.from(repository.saveAndFlush(new Routine(requestId, owner, request.title(), entries(request.sets()))));
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
        if (!Objects.equals(r.version, request.version())) throw new WorkoutConflict("루틴이 변경되었습니다. 다시 조회해 주세요.");
        r.replace(request.title(), entries(request.sets()));
        return View.from(repository.saveAndFlush(r));
    }
    public WorkoutController.View start(UUID id, String owner, UUID requestId) {
        Routine r = owned(id, owner);
        return workouts.startFromRoutine(owner, requestId, r.id, entries(View.from(r).sets()));
    }
    public void delete(UUID id, String owner, long version) {
        var found = repository.findByIdAndOwnerName(id, owner);
        if (found.isEmpty()) {
            if (retired.belongsTo(id, owner)) return;
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "루틴을 찾을 수 없습니다.");
        }
        Routine routine = found.get();
        if (routine.version.longValue() != version) throw new WorkoutConflict("루틴이 변경되었습니다. 다시 조회해 주세요.");
        retired.retire(id, owner);
        repository.delete(routine); repository.flush();
    }
    private Routine owned(UUID id, String owner) {
        return repository.findByIdAndOwnerName(id, owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "루틴을 찾을 수 없습니다."));
    }
    private List<Workout.Entry> entries(List<WorkoutController.SetInput> sets) {
        return sets.stream().map(s -> new Workout.Entry(s.exerciseName(), s.weight(), s.reps())).toList();
    }
}
