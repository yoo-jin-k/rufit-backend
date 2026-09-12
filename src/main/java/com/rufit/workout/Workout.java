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
    private UUID sourceRoutineId;
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
    void replaceEntries(List<Entry> replacement) {
        requireActive();
        if (replacement.isEmpty() || replacement.size() > 300) throw new IllegalArgumentException("세트는 1~300개여야 합니다.");
        entries.clear(); entries.addAll(replacement);
        completedSetIndexes.clear(); restEndsAt = null; restSetIndex = null;
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
        if (completedSetIndexes.size() != entries.size()) {
            if (!discardIncomplete) throw new WorkoutConflict("INCOMPLETE_SETS_CONFIRMATION_REQUIRED", "미완료 세트를 제외하고 종료할지 확인해 주세요.");
            List<Entry> completed = completedSetIndexes().stream().map(entries::get).toList();
            entries.clear(); entries.addAll(completed);
            completedSetIndexes.clear();
            for (int i = 0; i < entries.size(); i++) completedSetIndexes.add(i);
        }
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
        cancelledAt = now; restEndsAt = null; restSetIndex = null;
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
        @Column(nullable = false, length = 80) private String exerciseName;
        @Column(nullable = false, precision = 6, scale = 2) private BigDecimal weight;
        @Column(nullable = false) private int reps;
        protected Entry() {}
        public Entry(String name, BigDecimal weight, int reps) {
            if (name == null || name.isBlank() || name.trim().length() > 80) throw new IllegalArgumentException("운동 이름은 1~80자여야 합니다.");
            if (weight == null || weight.signum() < 0 || weight.compareTo(new BigDecimal("1500")) > 0 || weight.scale() > 2)
                throw new IllegalArgumentException("무게는 0~1500kg, 소수점 2자리까지 입력해 주세요.");
            if (reps < 1 || reps > 1000) throw new IllegalArgumentException("횟수는 1~1000이어야 합니다.");
            this.exerciseName = name.trim(); this.weight = weight; this.reps = reps;
        }
        public String name() { return exerciseName; }
        public BigDecimal weight() { return weight; }
        public int reps() { return reps; }
    }
}
