package com.rufit.workout;

import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.rufit.workout.WorkoutController.*;

@Service
@Transactional
public class WorkoutService {
    private final WorkoutRepository repository;
    WorkoutService(WorkoutRepository repository) { this.repository = repository; }
    public View start(String owner) {
        if (repository.findByOwnerNameAndFinishedAtIsNull(owner).isPresent()) throw new WorkoutConflict("진행 중인 운동이 있습니다.");
        return View.from(repository.saveAndFlush(new Workout(UUID.randomUUID(), owner, Instant.now())));
    }
    @Transactional(readOnly=true)
    public View get(UUID id, String owner) { return View.from(owned(id, owner)); }
    public View update(UUID id, String owner, Edit request) {
        Workout w = owned(id, owner); checkVersion(w, request.version());
        w.replaceEntries(request.sets().stream().map(s -> new Workout.Entry(s.exerciseName(), s.weight(), s.reps())).toList());
        return View.from(repository.saveAndFlush(w));
    }
    public View finish(UUID id, String owner, long version) {
        Workout w = owned(id, owner); checkVersion(w, version); w.finish(Instant.now());
        return View.from(repository.saveAndFlush(w));
    }
    private Workout owned(UUID id, String owner) {
        return repository.findByIdAndOwnerName(id, owner).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "운동을 찾을 수 없습니다."));
    }
    private void checkVersion(Workout w, long version) { if (w.version() != version) throw new WorkoutConflict("기록이 변경되었습니다. 다시 조회해 주세요."); }
}
