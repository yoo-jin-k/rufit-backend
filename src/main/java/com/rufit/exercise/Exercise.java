package com.rufit.exercise;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import com.rufit.workout.WorkoutConflict;

@Entity
@Table(name="exercises")
public class Exercise {
    @Id UUID id;
    @Column(length=100) String ownerName;
    @Column(nullable=false, length=80) String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=24) BodyPart bodyPart;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=24) Equipment equipment;
    Instant archivedAt;
    @Version Long version;
    protected Exercise() {}
    Exercise(UUID id, String owner, String name, BodyPart part, Equipment equipment) {
        this.id=id; this.ownerName=owner; update(name, part, equipment);
    }
    void update(String name, BodyPart part, Equipment equipment) {
        if (archivedAt != null) throw new WorkoutConflict("보관한 종목은 수정할 수 없습니다.");
        if (name == null || name.isBlank() || name.trim().length() > 80 || part == null || equipment == null)
            throw new IllegalArgumentException("종목 이름, 부위와 장비를 확인해 주세요.");
        this.name=name.trim(); this.bodyPart=part; this.equipment=equipment;
    }
    void archive() { if (archivedAt == null) archivedAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    public enum BodyPart { CHEST, BACK, SHOULDERS, LEGS, ARMS, CORE, FULL_BODY }
    public enum Equipment { BARBELL, DUMBBELL, MACHINE, CABLE, BODYWEIGHT, OTHER }
}
