package com.rufit.workout;

import static com.rufit.workout.WorkoutController.*;

/*
 * 운동 시작, 초안 변경, 완료 이력과 오프라인 가져오기를 조율합니다.
 * 요청 재시도의 식별자와 소유권을 확인하며 진행 중 운동의 중복 생성을 방지합니다.
 * 트랜잭션 격리 수준과 버전 충돌 처리는 동시 요청의 일관성에 영향을 주므로 함께 검토해야 합니다.
 */

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class WorkoutService {

  private final WorkoutRepository repository;
  private final RetiredWorkoutKeys retired;
  private final ExerciseSetResolver resolver;
  private final DraftOperations operations;
  private final com.rufit.exercise.ExerciseRestService restPreferences;

  WorkoutService(
    WorkoutRepository repository,
    RetiredWorkoutKeys retired,
    ExerciseSetResolver resolver,
    DraftOperations operations,
    com.rufit.exercise.ExerciseRestService restPreferences
  ) {
    this.repository = repository;
    this.retired = retired;
    this.resolver = resolver;
    this.operations = operations;
    this.restPreferences = restPreferences;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  // 인증된 사용자의 완료 이력을 날짜/페이지 조건으로 조회합니다.
  public History history(String owner, int page, int size) {
    return history(owner, page, size, null, "Asia/Seoul");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public History history(String owner, int page, int size, String date, String zone) {
    if (page < 0 || page > 10000 || size < 1 || size > 100) throw new IllegalArgumentException(
      "페이지와 조회 개수 범위를 확인해 주세요."
    );
    Pageable pagination = PageRequest.of(
      page,
      size,
      Sort.by(Sort.Order.desc("finishedAt"), Sort.Order.desc("id"))
    );
    Page<Workout> records;
    if (date == null) records = repository.findByOwnerNameAndFinishedAtIsNotNull(owner, pagination);
    else {
      WorkoutDateRange range = WorkoutDateRange.day(date, zone);
      records = repository.findDailyHistory(owner, range.from(), range.until(), pagination);
    }
    return new History(
      records.map(View::from).toList(),
      page,
      size,
      records.getTotalElements(),
      records.hasNext()
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  // 선택한 종목의 과거 세트만 조회하고 다른 계정의 기록은 제외합니다.
  public ExerciseHistory exerciseHistory(
    String owner,
    String exerciseName,
    UUID exerciseId,
    int page,
    int size
  ) {
    if (
      (exerciseId == null &&
        (exerciseName == null || exerciseName.isBlank() || exerciseName.trim().length() > 80)) ||
      (exerciseId != null && exerciseName != null)
    ) throw new IllegalArgumentException("운동 이름은 1~80자여야 합니다.");
    if (page < 0 || page > 10000 || size < 1 || size > 50) throw new IllegalArgumentException(
      "페이지와 조회 개수 범위를 확인해 주세요."
    );
    String name = exerciseId == null ? exerciseName.trim() : resolver.name(owner, exerciseId);
    Pageable pagination = PageRequest.of(
      page,
      size,
      Sort.by(Sort.Order.desc("finishedAt"), Sort.Order.desc("id"))
    );
    Page<Workout> records =
      exerciseId == null
        ? repository.findExerciseHistory(owner, name, pagination)
        : repository.findExerciseHistoryById(owner, exerciseId, pagination);
    var items = records
      .map(w ->
        new ExerciseRecord(
          w.id(),
          w.finishedAt(),
          w
            .entries()
            .stream()
            .filter(e ->
              exerciseId == null ? e.name().equals(name) : exerciseId.equals(e.exerciseId())
            )
            .map(e -> new SetInput(e.name(), e.weight(), e.reps(), e.exerciseId()))
            .toList()
        )
      )
      .toList();
    return new ExerciseHistory(
      name,
      items,
      page,
      size,
      records.getTotalElements(),
      records.hasNext()
    );
  }

  // 같은 시작 요청은 재사용하고 계정당 진행 중 운동은 하나만 허용합니다.
  public View start(String owner) {
    return start(owner, UUID.randomUUID());
  }

  public View start(String owner, UUID requestId) {
    var replay = repository.findByIdAndOwnerName(requestId, owner);
    if (replay.isPresent()) {
      if (
        replay.get().sourceRoutineId() != null || replay.get().importFingerprint() != null
      ) throw new WorkoutConflict("다른 시작 요청에 사용한 식별자입니다.");
      return View.from(replay.get());
    }
    requireReusableKey(requestId, owner);
    if (repository.existsById(requestId)) throw new WorkoutConflict(
      "요청 식별자를 다시 생성해 주세요."
    );
    if (
      repository.findByOwnerNameAndFinishedAtIsNullAndCancelledAtIsNull(owner).isPresent()
    ) throw new WorkoutConflict("진행 중인 운동이 있습니다.");
    return View.from(
      repository.saveAndFlush(
        new Workout(
          requestId,
          owner,
          Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        )
      )
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public java.util.Optional<View> replayRoutineStart(String owner, UUID requestId, UUID routineId) {
    var replay = repository.findByIdAndOwnerName(requestId, owner);
    if (
      replay.isPresent() && !routineId.equals(replay.get().sourceRoutineId())
    ) throw new WorkoutConflict("다른 시작 요청에 사용한 식별자입니다.");
    return replay.map(View::from);
  }

  // 가져오기 원문 지문을 비교해 같은 요청 재시도는 덮어쓰기 없이 처리합니다.
  public ImportResult importOffline(String owner, UUID key, OfflineImport request) {
    String fingerprint = OfflineImportFingerprint.of(request);
    var existing = repository.findByIdAndOwnerName(key, owner);
    if (existing.isPresent()) {
      Workout workout = existing.get();
      if (!fingerprint.equals(workout.importFingerprint())) throw new WorkoutConflict(
        "같은 가져오기 식별자에 다른 기록을 사용할 수 없습니다."
      );
      return new ImportResult(View.from(workout), 0, true);
    }
    requireReusableKey(key, owner);
    if (repository.existsById(key)) throw new WorkoutConflict("요청 식별자를 다시 생성해 주세요.");
    if (
      request.finishedAt() == null &&
      repository.findByOwnerNameAndFinishedAtIsNullAndCancelledAtIsNull(owner).isPresent()
    ) throw new WorkoutConflict("진행 중인 서버 운동이 있습니다. 먼저 두 기록을 확인해 주세요.");
    var entries = resolver.resolve(
      owner,
      request.sets().stream().map(DraftSet::set).toList(),
      java.util.List.of()
    );
    Workout imported = Workout.fromOffline(
      key,
      owner,
      request,
      entries,
      fingerprint,
      Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    );
    return new ImportResult(View.from(repository.saveAndFlush(imported)), 0, false);
  }

  // 루틴 세트를 복사해 원본 루틴과 독립된 운동을 시작합니다.
  public View startFromRoutine(
    String owner,
    UUID requestId,
    UUID routineId,
    java.util.List<Workout.Entry> sets
  ) {
    var replay = replayRoutineStart(owner, requestId, routineId);
    if (replay.isPresent()) return replay.get();
    requireReusableKey(requestId, owner);
    if (repository.existsById(requestId)) throw new WorkoutConflict(
      "요청 식별자를 다시 생성해 주세요."
    );
    if (
      repository.findByOwnerNameAndFinishedAtIsNullAndCancelledAtIsNull(owner).isPresent()
    ) throw new WorkoutConflict("진행 중인 운동이 있습니다.");
    Workout created = Workout.fromRoutine(
      requestId,
      owner,
      Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
      routineId,
      sets
    );
    return View.from(repository.saveAndFlush(created));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  // 앱 재접속 시 정지 상태를 포함한 현재 운동을 조회합니다.
  public java.util.Optional<View> active(String owner) {
    return repository.findByOwnerNameAndFinishedAtIsNullAndCancelledAtIsNull(owner).map(View::from);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  // 소유권을 확인한 운동을 화면용 응답으로 변환합니다.
  public View get(UUID id, String owner) {
    return View.from(owned(id, owner));
  }

  // 최신 버전을 확인한 뒤 전체 세트를 변경합니다.
  public View update(UUID id, String owner, Edit request) {
    Workout w = owned(id, owner);
    checkVersion(w, request.version());
    w.replaceEntries(resolver.resolve(owner, request.sets(), w.entries()));
    return View.from(repository.saveAndFlush(w));
  }

  // 메모만 수정하며 다른 운동 정보는 유지합니다.
  public View updateNote(UUID id, String owner, Note request) {
    Workout w = owned(id, owner);
    checkVersion(w, request.version());
    w.updateNote(request.note());
    return View.from(repository.saveAndFlush(w));
  }

  // 원자적 저장과 작업별 처리 확인을 묶어 응답 유실 후 같은 요청을 복구합니다.
  public DraftSave saveDraft(UUID id, String owner, Draft request) {
    var applied = operations.appliedVersion(id, request.operationId(), owner);
    Workout w = owned(id, owner);
    if (applied.isPresent()) return new DraftSave(View.from(w), applied.getAsLong(), true);
    checkVersion(w, request.version());
    var entries = resolver.resolve(
      owner,
      request.sets().stream().map(DraftSet::set).toList(),
      w.entries()
    );
    Instant deadline =
      request.restEndsAt() == null
        ? null
        : request.restEndsAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    w.saveDraft(
      request.operationId(),
      entries,
      request.sets().stream().map(DraftSet::completed).toList(),
      request.restSetIndex(),
      deadline,
      Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    );
    if (request.note() != null) w.updateNote(request.note());
    repository.saveAndFlush(w);
    operations.remember(id, request.operationId(), w.version());
    return new DraftSave(View.from(w), w.version(), false);
  }

  // 완료 이력을 최신 버전에서만 정정합니다.
  public View correctHistory(UUID id, String owner, Edit request) {
    Workout w = owned(id, owner);
    checkVersion(w, request.version());
    w.correctHistory(resolver.resolve(owner, request.sets(), w.entries()));
    return View.from(repository.saveAndFlush(w));
  }

  // 단일 세트 수정도 소유권과 버전 검사를 거칩니다.
  public View editSet(UUID id, String owner, int index, SetEdit request) {
    Workout w = owned(id, owner);
    checkVersion(w, request.version());
    SetInput set = request.set();
    w.editSet(index, resolver.resolve(owner, java.util.List.of(set), w.entries()).getFirst());
    return View.from(repository.saveAndFlush(w));
  }

  // 종목 접근 권한을 검사하고 새 세트를 추가합니다.
  public View appendSet(UUID id, String owner, SetEdit request) {
    Workout w = owned(id, owner);
    checkVersion(w, request.version());
    SetInput set = request.set();
    w.appendSet(resolver.resolve(owner, java.util.List.of(set), w.entries()).getFirst());
    return View.from(repository.saveAndFlush(w));
  }

  // 요청 번호가 최신 배열을 가리키는지 확인하고 서버에 저장된 원본 값만 복제합니다.
  public View duplicateSet(UUID id, String owner, int index, long version) {
    Workout w = owned(id, owner);
    checkVersion(w, version);
    w.duplicateSet(index);
    return View.from(repository.saveAndFlush(w));
  }

  // 선택한 세트를 삭제하되 다른 기기의 최신 변경은 덮어쓰지 않습니다.
  public View removeSet(UUID id, String owner, int index, long version) {
    Workout w = owned(id, owner);
    checkVersion(w, version);
    w.removeSet(index);
    return View.from(repository.saveAndFlush(w));
  }

  // 미완료 제외 여부를 반영해 운동을 종료합니다.
  public View finish(UUID id, String owner, long version, boolean discardIncomplete) {
    Workout w = owned(id, owner);
    checkVersion(w, version);
    w.finish(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS), discardIncomplete);
    return View.from(repository.saveAndFlush(w));
  }

  // 서버 시각으로 운동시간을 정지하고 수정 버전을 반환합니다.
  public View pause(UUID id, String owner, long version) {
    Workout w = owned(id, owner);
    checkVersion(w, version);
    w.pauseTimer(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    return View.from(repository.saveAndFlush(w));
  }

  // 서버에 저장된 정지 시각을 기준으로 운동시간을 재개합니다.
  public View resume(UUID id, String owner, long version) {
    Workout w = owned(id, owner);
    checkVersion(w, version);
    w.resumeTimer(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    return View.from(repository.saveAndFlush(w));
  }

  // 휴식을 생략하면 개인 설정→종목 추천→공통 기본값을 적용합니다.
  public View complete(UUID id, String owner, int index, Completion request) {
    Workout w = owned(id, owner);
    checkVersion(w, request.version());
    if (index < 0 || index >= w.entries().size()) throw new IllegalArgumentException(
      "세트 번호를 확인해 주세요."
    );
    int seconds =
      request.restSeconds() != null
        ? request.restSeconds()
        : request.completed()
          ? restPreferences.effectiveSeconds(w.entries().get(index).exerciseId(), owner)
          : 0;
    w.completeSet(
      index,
      request.completed(),
      seconds,
      Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    );
    return View.from(repository.saveAndFlush(w));
  }

  // 확인된 취소 요청을 소유자의 최신 운동에 적용합니다.
  public View cancel(UUID id, String owner, long version) {
    Workout w = owned(id, owner);
    checkVersion(w, version);
    w.cancel(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    return View.from(repository.saveAndFlush(w));
  }

  // 운동 진행시간을 건드리지 않고 현재 휴식만 제거합니다.
  public View skipRest(UUID id, String owner, long version) {
    Workout w = owned(id, owner);
    checkVersion(w, version);
    w.skipRest();
    return View.from(repository.saveAndFlush(w));
  }

  // 버전 검사로 동일 조절 요청이 두 번 적용되는 것을 방지합니다.
  public View adjustRest(UUID id, String owner, long version, int seconds) {
    Workout w = owned(id, owner);
    checkVersion(w, version);
    w.adjustRest(seconds, Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    return View.from(repository.saveAndFlush(w));
  }

  // 닫힌 기록을 삭제하고 재시도로 되살아나지 않도록 ID를 보관합니다.
  public void deleteHistory(UUID id, String owner, long version) {
    var found = repository.findByIdAndOwnerName(id, owner);
    if (found.isEmpty()) {
      if (retired.belongsTo(id, owner)) return;
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "운동을 찾을 수 없습니다.");
    }
    Workout workout = found.get();
    checkVersion(workout, version);
    if (workout.finishedAt() == null && workout.cancelledAt() == null) throw new WorkoutConflict(
      "진행 중인 운동은 먼저 취소해 주세요."
    );
    retired.retire(id, owner);
    repository.delete(workout);
    repository.flush();
  }

  private void requireReusableKey(UUID id, String owner) {
    if (retired.belongsTo(id, owner)) throw new ResponseStatusException(
      HttpStatus.GONE,
      "삭제한 기록의 요청 식별자입니다."
    );
    if (retired.exists(id)) throw new WorkoutConflict("요청 식별자를 다시 생성해 주세요.");
  }

  private Workout owned(UUID id, String owner) {
    return repository
      .findByIdAndOwnerName(id, owner)
      .orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "운동을 찾을 수 없습니다.")
      );
  }

  private void checkVersion(Workout w, long version) {
    if (w.version() != version) throw new WorkoutConflict(
      "기록이 변경되었습니다. 다시 조회해 주세요."
    );
  }
}
