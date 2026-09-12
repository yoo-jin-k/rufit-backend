package com.rufit.workout;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.rufit.workout.WorkoutController.*;

@Service
@Transactional
public class WorkoutService {
    private final WorkoutRepository repository;
    private final RetiredWorkoutKeys retired;
    private final ExerciseSetResolver resolver;
    private final DraftOperations operations;
    WorkoutService(WorkoutRepository repository, RetiredWorkoutKeys retired, ExerciseSetResolver resolver, DraftOperations operations) { this.repository = repository; this.retired = retired; this.resolver=resolver; this.operations=operations; }
    @Transactional(readOnly=true)
    public History history(String owner, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw new IllegalArgumentException("페이지와 조회 개수 범위를 확인해 주세요.");
        Page<Workout> records = repository.findByOwnerNameAndFinishedAtIsNotNull(owner,
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("finishedAt"), Sort.Order.desc("id"))));
        return new History(records.map(View::from).toList(), page, size, records.getTotalElements(), records.hasNext());
    }
    @Transactional(readOnly=true)
    public ExerciseHistory exerciseHistory(String owner, String exerciseName, UUID exerciseId, int page, int size) {
        if ((exerciseId == null && (exerciseName == null || exerciseName.isBlank() || exerciseName.trim().length() > 80)) || (exerciseId != null && exerciseName != null))
            throw new IllegalArgumentException("운동 이름은 1~80자여야 합니다.");
        if (page < 0 || page > 10000 || size < 1 || size > 50)
            throw new IllegalArgumentException("페이지와 조회 개수 범위를 확인해 주세요.");
        String name = exerciseId == null ? exerciseName.trim() : resolver.name(owner, exerciseId);
        Pageable pagination=PageRequest.of(page,size,Sort.by(Sort.Order.desc("finishedAt"),Sort.Order.desc("id")));
        Page<Workout> records = exerciseId == null ? repository.findExerciseHistory(owner,name,pagination)
            : repository.findExerciseHistoryById(owner,exerciseId,pagination);
        var items = records.map(w -> new ExerciseRecord(w.id(), w.finishedAt(),
            w.entries().stream().filter(e -> exerciseId == null ? e.name().equals(name) : exerciseId.equals(e.exerciseId()))
                .map(e -> new SetInput(e.name(), e.weight(), e.reps(), e.exerciseId())).toList())).toList();
        return new ExerciseHistory(name, items, page, size, records.getTotalElements(), records.hasNext());
    }
    public View start(String owner) { return start(owner, UUID.randomUUID()); }
    public View start(String owner, UUID requestId) {
        var replay = repository.findByIdAndOwnerName(requestId, owner);
        if (replay.isPresent()) {
            if (replay.get().sourceRoutineId() != null) throw new WorkoutConflict("다른 시작 요청에 사용한 식별자입니다.");
            return View.from(replay.get());
        }
        requireReusableKey(requestId, owner);
        if (repository.existsById(requestId)) throw new WorkoutConflict("요청 식별자를 다시 생성해 주세요.");
        if (repository.findByOwnerNameAndFinishedAtIsNullAndCancelledAtIsNull(owner).isPresent()) throw new WorkoutConflict("진행 중인 운동이 있습니다.");
        return View.from(repository.saveAndFlush(new Workout(requestId, owner, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS))));
    }
    @Transactional(readOnly=true)
    public java.util.Optional<View> replayRoutineStart(String owner, UUID requestId, UUID routineId) {
        var replay = repository.findByIdAndOwnerName(requestId, owner);
        if (replay.isPresent() && !routineId.equals(replay.get().sourceRoutineId()))
            throw new WorkoutConflict("다른 시작 요청에 사용한 식별자입니다.");
        return replay.map(View::from);
    }
    public View startFromRoutine(String owner, UUID requestId, UUID routineId, java.util.List<Workout.Entry> sets) {
        var replay = replayRoutineStart(owner, requestId, routineId);
        if (replay.isPresent()) return replay.get();
        requireReusableKey(requestId, owner);
        if (repository.existsById(requestId)) throw new WorkoutConflict("요청 식별자를 다시 생성해 주세요.");
        if (repository.findByOwnerNameAndFinishedAtIsNullAndCancelledAtIsNull(owner).isPresent()) throw new WorkoutConflict("진행 중인 운동이 있습니다.");
        Workout created = Workout.fromRoutine(requestId, owner, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), routineId, sets);
        return View.from(repository.saveAndFlush(created));
    }
    @Transactional(readOnly=true)
    public java.util.Optional<View> active(String owner) {
        return repository.findByOwnerNameAndFinishedAtIsNullAndCancelledAtIsNull(owner).map(View::from);
    }
    @Transactional(readOnly=true)
    public View get(UUID id, String owner) { return View.from(owned(id, owner)); }
    public View update(UUID id, String owner, Edit request) {
        Workout w = owned(id, owner); checkVersion(w, request.version());
        w.replaceEntries(resolver.resolve(owner,request.sets(),w.entries()));
        return View.from(repository.saveAndFlush(w));
    }
    public View updateNote(UUID id,String owner,Note request) {
        Workout w=owned(id,owner); checkVersion(w,request.version());
        w.updateNote(request.note());
        return View.from(repository.saveAndFlush(w));
    }
    public DraftSave saveDraft(UUID id,String owner,Draft request) {
        var applied=operations.appliedVersion(id,request.operationId(),owner);
        Workout w=owned(id,owner);
        if (applied.isPresent()) return new DraftSave(View.from(w),applied.getAsLong(),true);
        checkVersion(w,request.version());
        var entries=resolver.resolve(owner,request.sets().stream().map(DraftSet::set).toList(),w.entries());
        Instant deadline=request.restEndsAt()==null?null:request.restEndsAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        w.saveDraft(request.operationId(),entries,request.sets().stream().map(DraftSet::completed).toList(),request.restSetIndex(),deadline,
            Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        if (request.note()!=null) w.updateNote(request.note());
        repository.saveAndFlush(w); operations.remember(id,request.operationId(),w.version());
        return new DraftSave(View.from(w),w.version(),false);
    }
    public View correctHistory(UUID id, String owner, Edit request) {
        Workout w = owned(id, owner); checkVersion(w, request.version());
        w.correctHistory(resolver.resolve(owner,request.sets(),w.entries()));
        return View.from(repository.saveAndFlush(w));
    }
    public View editSet(UUID id, String owner, int index, SetEdit request) {
        Workout w = owned(id, owner); checkVersion(w, request.version());
        SetInput set = request.set();
        w.editSet(index, resolver.resolve(owner,java.util.List.of(set),w.entries()).getFirst());
        return View.from(repository.saveAndFlush(w));
    }
    public View appendSet(UUID id, String owner, SetEdit request) {
        Workout w = owned(id, owner); checkVersion(w, request.version());
        SetInput set = request.set();
        w.appendSet(resolver.resolve(owner,java.util.List.of(set),w.entries()).getFirst());
        return View.from(repository.saveAndFlush(w));
    }
    public View removeSet(UUID id, String owner, int index, long version) {
        Workout w = owned(id, owner); checkVersion(w, version); w.removeSet(index);
        return View.from(repository.saveAndFlush(w));
    }
    public View finish(UUID id, String owner, long version, boolean discardIncomplete) {
        Workout w = owned(id, owner); checkVersion(w, version); w.finish(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), discardIncomplete);
        return View.from(repository.saveAndFlush(w));
    }
    public View complete(UUID id, String owner, int index, Completion request) {
        Workout w = owned(id, owner); checkVersion(w, request.version());
        w.completeSet(index, request.completed(), request.restSeconds(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        return View.from(repository.saveAndFlush(w));
    }
    public View cancel(UUID id, String owner, long version) {
        Workout w = owned(id, owner); checkVersion(w, version);
        w.cancel(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        return View.from(repository.saveAndFlush(w));
    }
    public View skipRest(UUID id, String owner, long version) {
        Workout w = owned(id, owner); checkVersion(w, version); w.skipRest();
        return View.from(repository.saveAndFlush(w));
    }
    public void deleteHistory(UUID id, String owner, long version) {
        var found = repository.findByIdAndOwnerName(id, owner);
        if (found.isEmpty()) {
            if (retired.belongsTo(id, owner)) return;
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "운동을 찾을 수 없습니다.");
        }
        Workout workout = found.get(); checkVersion(workout, version);
        if (workout.finishedAt() == null && workout.cancelledAt() == null)
            throw new WorkoutConflict("진행 중인 운동은 먼저 취소해 주세요.");
        retired.retire(id, owner);
        repository.delete(workout); repository.flush();
    }
    private void requireReusableKey(UUID id, String owner) {
        if (retired.belongsTo(id, owner)) throw new ResponseStatusException(HttpStatus.GONE, "삭제한 기록의 요청 식별자입니다.");
        if (retired.exists(id)) throw new WorkoutConflict("요청 식별자를 다시 생성해 주세요.");
    }
    private Workout owned(UUID id, String owner) {
        return repository.findByIdAndOwnerName(id, owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "운동을 찾을 수 없습니다."));
    }
    private void checkVersion(Workout w, long version) { if (w.version() != version) throw new WorkoutConflict("기록이 변경되었습니다. 다시 조회해 주세요."); }
}
