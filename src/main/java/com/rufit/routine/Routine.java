package com.rufit.routine;

import com.rufit.workout.Workout;
import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name="routines")
class Routine {
    @Id UUID id;
    @Column(nullable=false, length=100) String ownerName;
    @Column(nullable=false, length=80) String title;
    @Version long version;
    @ElementCollection
    @CollectionTable(name="routine_entries", joinColumns=@JoinColumn(name="routine_id"))
    @OrderColumn(name="entry_order")
    List<Workout.Entry> entries = new ArrayList<>();
    protected Routine() {}
    Routine(String owner, String title, List<Workout.Entry> entries) {
        id = UUID.randomUUID(); ownerName = owner; replace(title, entries);
    }
    void replace(String title, List<Workout.Entry> entries) {
        if (title == null || title.isBlank() || title.trim().length() > 80 || entries.isEmpty() || entries.size() > 300)
            throw new IllegalArgumentException("루틴 이름과 1~300개 세트를 입력해 주세요.");
        this.title = title.trim(); this.entries.clear(); this.entries.addAll(entries);
    }
}
