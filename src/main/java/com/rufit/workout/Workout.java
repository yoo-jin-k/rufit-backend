package com.rufit.workout;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

@Entity
@Table(name = "workouts")
public class Workout {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String ownerName;
    @Column(nullable = false) private Instant startedAt;
    private Instant finishedAt;
    private Instant cancelledAt;
    private Instant pausedAt;
    @Column(nullable=false) private long pausedDurationMs;
    private UUID sourceRoutineId;
    private UUID lastDraftOperationId;
    @Column(length=64) private String importFingerprint;
    @Column(nullable=false,length=2000) private String note = "";
    @Version private Long version;
    @ElementCollection
    @CollectionTable(name = "workout_entries", joinColumns = @JoinColumn(name = "workout_id"))
    @OrderColumn(name = "entry_order")
    private List<Entry> entries = new ArrayList<>();
    @ElementCollection
    @CollectionTable(name="completed_workout_sets", joinColumns=@JoinColumn(name="workout_id"))
    @Column(name="set_index", nullable=false)
    private Set<Integer> completedSetIndexes = new HashSet<>();
    private Instant restEndsAt;
    private Integer restSetIndex;
    protected Workout() {}
    Workout(UUID id, String owner, Instant now) { this.id = id; ownerName = owner; startedAt = now; }
    static Workout fromRoutine(UUID id, String owner, Instant now, UUID routineId, List<Entry> sets) {
        Workout workout = new Workout(id, owner, now);
        workout.sourceRoutineId = routineId;
        workout.replaceEntries(sets);
        return workout;
    }
    UUID sourceRoutineId() { return sourceRoutineId; }
    String importFingerprint() { return importFingerprint; }
    static Workout fromOffline(UUID id,String owner,WorkoutController.OfflineImport request,List<Entry> sets,String fingerprint,Instant now) {
        if (!Boolean.TRUE.equals(request.confirmed()) || request.startedAt()==null || request.startedAt().isBefore(Instant.EPOCH) || request.startedAt().isAfter(now))
            throw new IllegalArgumentException("가져오기 확인과 실제 운동 시작 시각을 확인해 주세요.");
        if (request.finishedAt()!=null && (request.finishedAt().isBefore(request.startedAt()) || request.finishedAt().isAfter(now)))
            throw new IllegalArgumentException("종료 시각은 시작 시각부터 현재 시각 사이여야 합니다.");
        if (request.finishedAt()!=null && (request.restSetIndex()!=null || request.restEndsAt()!=null))
            throw new IllegalArgumentException("완료된 운동에는 진행 중인 휴식을 가져올 수 없습니다.");
        Workout workout=new Workout(id,owner,request.startedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        workout.pausedAt=request.pausedAt()==null ? null : request.pausedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        workout.pausedDurationMs=request.pausedDurationMs()==null ? 0 : request.pausedDurationMs();
        if (workout.pausedAt!=null && (request.finishedAt()!=null || workout.pausedAt.isBefore(workout.startedAt) || workout.pausedAt.isAfter(now)) ||
            workout.pausedDurationMs<0 || workout.pausedDurationMs>java.time.Duration.between(workout.startedAt,
                workout.pausedAt!=null ? workout.pausedAt : request.finishedAt()!=null ? request.finishedAt() : now).toMillis())
            throw new IllegalArgumentException("정지 시각과 누적 정지 시간을 확인해 주세요.");
        Instant deadline=request.restEndsAt()==null ? null : request.restEndsAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        workout.saveDraft(id,sets,request.sets().stream().map(WorkoutController.DraftSet::completed).toList(),request.restSetIndex(),deadline,now);
        workout.updateNote(request.note());
        if (request.finishedAt()!=null) workout.finish(request.finishedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),false);
        workout.importFingerprint=fingerprint;
        return workout;
    }
    String note() { return note; }
    void updateNote(String value) {
        if (cancelledAt != null) throw new WorkoutConflict("취소한 운동은 수정할 수 없습니다.");
        if (value == null || value.length() > 2000) throw new IllegalArgumentException("메모는 2000자 이내여야 합니다.");
        note = value;
    }
    void replaceEntries(List<Entry> replacement) {
        requireActive();
        if (replacement.isEmpty() || replacement.size() > 300) throw new IllegalArgumentException("세트는 1~300개여야 합니다.");
        entries.clear(); entries.addAll(replacement);
        completedSetIndexes.clear(); restEndsAt = null; restSetIndex = null;
    }
    void saveDraft(UUID operationId,List<Entry> replacement,List<Boolean> completed,Integer restIndex,Instant deadline,Instant now) {
        requireActive();
        if (replacement.size()>300 || replacement.size()!=completed.size() || completed.stream().anyMatch(java.util.Objects::isNull) || operationId==null)
            throw new IllegalArgumentException("세트 구성과 완료 상태를 확인해 주세요.");
        if ((restIndex==null)!=(deadline==null)) throw new IllegalArgumentException("휴식 대상과 종료시각을 함께 보내 주세요.");
        if (restIndex!=null && (restIndex<0 || restIndex>=replacement.size() || !completed.get(restIndex)))
            throw new IllegalArgumentException("휴식은 완료한 세트에만 연결할 수 있습니다.");
        if (deadline!=null && deadline.isAfter(now.plusSeconds(3600))) throw new IllegalArgumentException("휴식은 최대 3600초까지 설정할 수 있습니다.");
        entries.clear(); entries.addAll(replacement); completedSetIndexes.clear();
        for (int i=0;i<completed.size();i++) if (completed.get(i)) completedSetIndexes.add(i);
        restEndsAt=deadline!=null && deadline.isAfter(now) ? deadline : null;
        restSetIndex=restEndsAt==null ? null : restIndex;
        lastDraftOperationId=operationId;
    }
    Integer restSetIndex() { return restSetIndex; }
    void correctHistory(List<Entry> replacement) {
        if (finishedAt == null) throw new WorkoutConflict("완료한 운동 기록만 정정할 수 있습니다.");
        if (replacement.isEmpty() || replacement.size() > 300) throw new IllegalArgumentException("완료 세트는 1~300개여야 합니다.");
        entries.clear(); entries.addAll(replacement);
        completedSetIndexes.clear();
        for (int i = 0; i < entries.size(); i++) completedSetIndexes.add(i);
    }
    void editSet(int index, Entry replacement) {
        requireActive(); requireSetIndex(index);
        entries.set(index, replacement);
    }
    void appendSet(Entry entry) {
        requireActive();
        if (entries.size() >= 300) throw new IllegalArgumentException("세트는 최대 300개까지 추가할 수 있습니다.");
        entries.add(entry);
    }
    void removeSet(int index) {
        requireActive(); requireSetIndex(index);
        entries.remove(index);
        Set<Integer> shifted = new HashSet<>();
        for (int completed : completedSetIndexes) {
            if (completed != index) shifted.add(completed > index ? completed - 1 : completed);
        }
        completedSetIndexes.clear(); completedSetIndexes.addAll(shifted);
        if (restSetIndex != null) {
            if (restSetIndex == index) { restSetIndex = null; restEndsAt = null; }
            else if (restSetIndex > index) restSetIndex--;
        }
    }
    private void requireSetIndex(int index) {
        if (index < 0 || index >= entries.size()) throw new IllegalArgumentException("세트 번호를 확인해 주세요.");
    }
    void finish(Instant now) { finish(now, false); }
    void finish(Instant now, boolean discardIncomplete) {
        requireActive();
        if (completedSetIndexes.isEmpty()) throw new IllegalArgumentException("완료한 세트가 1개 이상 있어야 종료할 수 있습니다.");
        if (now.isBefore(startedAt)) throw new IllegalArgumentException("종료 시간이 시작 시간보다 빠릅니다.");
        if (pausedAt!=null && now.isBefore(pausedAt)) throw new IllegalArgumentException("종료 시간이 정지 시간보다 빠릅니다.");
        if (completedSetIndexes.size() != entries.size()) {
            if (!discardIncomplete) throw new WorkoutConflict("INCOMPLETE_SETS_CONFIRMATION_REQUIRED", "미완료 세트를 제외하고 종료할지 확인해 주세요.");
            List<Entry> completed = completedSetIndexes().stream().map(entries::get).toList();
            entries.clear(); entries.addAll(completed);
            completedSetIndexes.clear();
            for (int i = 0; i < entries.size(); i++) completedSetIndexes.add(i);
        }
        resumeTimer(now);
        finishedAt = now; restEndsAt = null; restSetIndex = null;
    }
    void completeSet(int index, boolean completed, int restSeconds, Instant now) {
        requireActive();
        if (index < 0 || index >= entries.size() || restSeconds < 0 || restSeconds > 3600)
            throw new IllegalArgumentException("세트 번호와 휴식 시간(0~3600초)을 확인해 주세요.");
        if (completed && completedSetIndexes.add(index)) {
            restEndsAt = restSeconds == 0 ? null : now.plusSeconds(restSeconds);
            restSetIndex = restSeconds == 0 ? null : index;
        } else if (!completed && completedSetIndexes.remove(index) && java.util.Objects.equals(restSetIndex, index)) {
            restEndsAt = null; restSetIndex = null;
        }
    }
    void cancel(Instant now) {
        requireActive();
        if (now.isBefore(startedAt)) throw new IllegalArgumentException("취소 시간이 시작 시간보다 빠릅니다.");
        resumeTimer(now);
        cancelledAt = now; restEndsAt = null; restSetIndex = null;
    }
    void pauseTimer(Instant now) {
        requireActive();
        if (pausedAt!=null) return;
        if (now.isBefore(startedAt) || pausedDurationMs>java.time.Duration.between(startedAt,now).toMillis())
            throw new IllegalArgumentException("운동 시각을 확인해 주세요.");
        pausedAt=now;
    }
    void resumeTimer(Instant now) {
        requireActive();
        if (pausedAt==null) return;
        if (now.isBefore(pausedAt)) throw new IllegalArgumentException("진행 시각이 정지 시각보다 빠릅니다.");
        pausedDurationMs=Math.addExact(pausedDurationMs,java.time.Duration.between(pausedAt,now).toMillis());
        pausedAt=null;
    }
    Instant pausedAt() { return pausedAt; }
    long pausedDurationMs() { return pausedDurationMs; }
    long elapsedMillis(Instant now) {
        Instant until=finishedAt!=null ? finishedAt : cancelledAt!=null ? cancelledAt : pausedAt!=null ? pausedAt : now;
        return Math.max(0,java.time.Duration.between(startedAt,until).toMillis()-pausedDurationMs);
    }
    Instant cancelledAt() { return cancelledAt; }
    void skipRest() { requireActive(); restEndsAt = null; restSetIndex = null; }
    List<Integer> completedSetIndexes() { return completedSetIndexes.stream().sorted().toList(); }
    Instant restEndsAt() { return restEndsAt; }
    private void requireActive() { if (finishedAt != null || cancelledAt != null) throw new WorkoutConflict("이미 종료한 운동입니다."); }
    UUID id() { return id; }
    String owner() { return ownerName; }
    Instant startedAt() { return startedAt; }
    Instant finishedAt() { return finishedAt; }
    long version() { return version == null ? 0 : version; }
    List<Entry> entries() { return List.copyOf(entries); }

    @Embeddable
    public static class Entry {
        private UUID exerciseId;
        @Column(nullable = false, length = 80) private String exerciseName;
        @Column(nullable = false, precision = 6, scale = 2) private BigDecimal weight;
        @Column(nullable = false) private int reps;
        protected Entry() {}
        public Entry(String name, BigDecimal weight, int reps) { this(null,name,weight,reps); }
        public Entry(UUID exerciseId, String name, BigDecimal weight, int reps) {
            this.exerciseId=exerciseId;
            if (name == null || name.isBlank() || name.trim().length() > 80) throw new IllegalArgumentException("운동 이름은 1~80자여야 합니다.");
            if (weight == null || weight.signum() < 0 || weight.compareTo(new BigDecimal("1500")) > 0 || weight.scale() > 2)
                throw new IllegalArgumentException("무게는 0~1500kg, 소수점 2자리까지 입력해 주세요.");
            if (reps < 1 || reps > 1000) throw new IllegalArgumentException("횟수는 1~1000이어야 합니다.");
            this.exerciseName = name.trim(); this.weight = weight; this.reps = reps;
        }
        public UUID exerciseId() { return exerciseId; }
        public String name() { return exerciseName; }
        public BigDecimal weight() { return weight; }
        public int reps() { return reps; }
    }
}
