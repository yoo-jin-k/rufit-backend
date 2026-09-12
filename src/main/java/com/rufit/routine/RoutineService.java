package com.rufit.routine;

import com.rufit.workout.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.server.ResponseStatusException;
import static com.rufit.routine.RoutineController.*;

@Service
@Transactional(isolation=Isolation.REPEATABLE_READ)
public class RoutineService {
    private final RoutineRepository repository;
    private final WorkoutService workouts;
    private final RetiredRoutineKeys retired;
    private final ExerciseSetResolver resolver;
    RoutineService(RoutineRepository repository, WorkoutService workouts, RetiredRoutineKeys retired, ExerciseSetResolver resolver) { this.repository=repository; this.workouts=workouts; this.retired=retired; this.resolver=resolver; }
    public View create(String owner, Create request, UUID requestId) {
        var replay = repository.findByIdAndOwnerName(requestId, owner);
        if (replay.isPresent()) {
            if (replay.get().sourceRoutineId != null) throw new WorkoutConflict("복사 요청에 사용한 식별자입니다.");
            return View.from(replay.get());
        }
        if (retired.belongsTo(requestId, owner)) throw new ResponseStatusException(HttpStatus.GONE, "삭제한 루틴입니다.");
        if (retired.exists(requestId) || repository.existsById(requestId)) throw new WorkoutConflict("요청 식별자를 다시 생성해 주세요.");
        return View.from(repository.saveAndFlush(new Routine(requestId, owner, request.title(), resolver.resolve(owner,request.sets(),List.of()))));
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public View copy(UUID sourceId, String owner, Copy request, UUID requestId) {
        var replay = repository.findByIdAndOwnerName(requestId,owner);
        if (replay.isPresent()) {
            if (!sourceId.equals(replay.get().sourceRoutineId)) throw new WorkoutConflict("다른 생성 요청에 사용한 식별자입니다.");
            return View.from(replay.get());
        }
        if (retired.belongsTo(requestId,owner)) throw new ResponseStatusException(HttpStatus.GONE,"삭제한 루틴입니다.");
        if (retired.exists(requestId) || repository.existsById(requestId)) throw new WorkoutConflict("요청 식별자를 다시 생성해 주세요.");
        Routine source=owned(sourceId,owner);
        if (!Objects.equals(source.version,request.version())) throw new WorkoutConflict("원본 루틴이 변경되었습니다. 다시 조회해 주세요.");
        var entries=source.entries.stream().map(e -> new Workout.Entry(e.exerciseId(),e.name(),e.weight(),e.reps())).toList();
        Routine copy=new Routine(requestId,owner,request.title(),entries);
        copy.sourceRoutineId=source.id;
        return View.from(repository.saveAndFlush(copy));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View get(UUID id, String owner) { return View.from(owned(id, owner)); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<View> list(String owner, int page) {
        if (page < 0 || page > 10000) throw new IllegalArgumentException("페이지 범위를 확인해 주세요.");
        return repository.findByOwnerName(owner, PageRequest.of(page, 20, Sort.by("id"))).map(View::from).toList();
    }
    public View update(UUID id, String owner, Edit request) {
        Routine r = owned(id, owner);
        if (!Objects.equals(r.version, request.version())) throw new WorkoutConflict("루틴이 변경되었습니다. 다시 조회해 주세요.");
        r.replace(request.title(), resolver.resolve(owner,request.sets(),r.entries));
        return View.from(repository.saveAndFlush(r));
    }
    public WorkoutController.View start(UUID id, String owner, UUID requestId) {
        var replay = workouts.replayRoutineStart(owner, requestId, id);
        if (replay.isPresent()) return replay.get();
        Routine r = owned(id, owner);
        return workouts.startFromRoutine(owner, requestId, r.id, r.entries.stream().map(e -> new Workout.Entry(e.exerciseId(),e.name(),e.weight(),e.reps())).toList());
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
}
