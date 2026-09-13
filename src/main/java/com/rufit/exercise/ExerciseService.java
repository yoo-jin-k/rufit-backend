package com.rufit.exercise;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.rufit.workout.WorkoutConflict;
import java.util.*;
import static com.rufit.exercise.ExerciseController.*;

@Service
@Transactional(isolation=Isolation.REPEATABLE_READ)
public class ExerciseService {
    private final ExerciseRepository repository;
    ExerciseService(ExerciseRepository repository) { this.repository=repository; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Results search(String owner, String query, boolean includeArchived, Exercise.BodyPart part, Exercise.Equipment equipment, int page, int size) {
        if (query.length() > 80 || page < 0 || page > 10000 || size < 1 || size > 100)
            throw new IllegalArgumentException("검색어와 조회 범위를 확인해 주세요.");
        String pattern="%"+query.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")+"%";
        Page<Exercise> found=repository.search(owner, pattern, includeArchived, part, equipment, PageRequest.of(page,size,Sort.by("name","id")));
        return new Results(found.map(View::from).toList(), page, size, found.getTotalElements(), found.hasNext());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View get(UUID id, String owner) {
        Exercise e=repository.findById(id).orElseThrow(this::notFound);
        if (e.ownerName != null && !e.ownerName.equals(owner)) throw notFound();
        return View.from(e);
    }
    public View create(String owner, UUID key, Create request) {
        var replay=repository.findByIdAndOwnerName(key,owner);
        if (replay.isPresent()) return View.from(replay.get());
        if (repository.existsById(key)) throw new WorkoutConflict("요청 식별자를 다시 생성해 주세요.");
        return View.from(repository.saveAndFlush(new Exercise(key,owner,request.name(),request.bodyPart(),request.equipment())));
    }
    public View update(UUID id, String owner, Edit request) {
        Exercise e=owned(id,owner); checkVersion(e,request.version());
        e.update(request.name(),request.bodyPart(),request.equipment()); return View.from(repository.saveAndFlush(e));
    }
    public View archive(UUID id, String owner, long version) {
        Exercise e=owned(id,owner); checkVersion(e,version); e.archive(); return View.from(repository.saveAndFlush(e));
    }
    public View restore(UUID id, String owner, long version) {
        Exercise e=owned(id,owner); checkVersion(e,version); e.archivedAt=null; return View.from(repository.saveAndFlush(e));
    }
    private Exercise owned(UUID id,String owner) { return repository.findByIdAndOwnerName(id,owner).orElseThrow(this::notFound); }
    private void checkVersion(Exercise e,long version) { if (e.version.longValue()!=version) throw new WorkoutConflict("종목이 변경되었습니다. 다시 조회해 주세요."); }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"종목을 찾을 수 없습니다."); }
}
