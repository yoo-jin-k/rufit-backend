package com.rufit.exercise;

import jakarta.persistence.*;
import java.util.UUID;

/** 공용 카탈로그의 검색용 이름/별칭. 운동 기록에 저장하는 정식 이름과 ID는 바꾸지 않는다. */
@Entity
@Table(name="exercise_search_aliases")
class ExerciseSearchAlias {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) UUID exerciseId;
    @Column(name="normalized_alias",nullable=false,length=160) String searchKey;
    protected ExerciseSearchAlias() {}
}
