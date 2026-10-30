package com.rufit.exercise;

import static com.rufit.exercise.ExerciseRestController.*;

/*
 * 종목별 권장 휴식과 사용자 설정을 조회하고 변경합니다.
 * 기본 추천 값과 개인 설정을 구분하여 추천 사용으로 되돌리는 동작을 유지합니다.
 */

import com.rufit.profile.RestPreferencesService;
import com.rufit.workout.WorkoutConflict;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class ExerciseRestService {

  private final JdbcTemplate jdbc;
  private final ExerciseService exercises;
  private final RestPreferencesService defaults;

  ExerciseRestService(
    JdbcTemplate jdbc,
    ExerciseService exercises,
    RestPreferencesService defaults
  ) {
    this.jdbc = jdbc;
    this.exercises = exercises;
    this.defaults = defaults;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  // 읽기만으로 설정을 생성하지 않고, 추천/개인/기본 중 적용값을 알려드립니다.
  public View get(UUID id, String owner) {
    var exercise = exercises.get(id, owner);
    var preference = jdbc
      .query(
        "select version,rest_seconds from exercise_rest_preferences where owner_name=? and exercise_id=?",
        (rs, row) -> new Preference(rs.getLong(1), rs.getObject(2, Integer.class)),
        owner,
        id
      )
      .stream()
      .findFirst()
      .orElse(new Preference(0, null));
    Integer personal = preference.seconds(),
      recommended = exercise.recommendedRestSeconds();
    Source source =
      personal != null
        ? Source.PERSONAL
        : recommended != null
          ? Source.RECOMMENDED
          : Source.DEFAULT;
    int effective =
      personal != null
        ? personal
        : recommended != null
          ? recommended
          : defaults.get(owner).defaultRestSeconds();
    return new View(id, preference.version(), recommended, personal, effective, source);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  // 종목 ID가 없는 직접 입력 세트는 계정 공통 휴식시간을 사용합니다.
  public int effectiveSeconds(UUID id, String owner) {
    return id == null
      ? defaults.get(owner).defaultRestSeconds()
      : get(id, owner).effectiveRestSeconds();
  }

  // 개인 초 또는 추천 사용을 저장합니다. 추천 복귀에도 버전을 남겨 과거 요청을 방지합니다.
  public View save(UUID id, String owner, Edit edit) {
    exercises.get(id, owner);
    if (
      edit.version() == null ||
      edit.version() < 0 ||
      edit.mode() == null ||
      (edit.mode() == Mode.RECOMMENDED && edit.restSeconds() != null) ||
      (edit.mode() == Mode.PERSONAL &&
        (edit.restSeconds() == null || edit.restSeconds() < 0 || edit.restSeconds() > 3600))
    ) throw new IllegalArgumentException(
      "권장 사용 또는 0~3600초의 개인 휴식시간을 선택해 주세요."
    );
    // 초기화한 행도 유지하여 오래된 버전이 이후 선택을 덮어쓰지 못하도록 합니다.
    int changed =
      edit.version() == 0
        ? jdbc.update(
            """
            insert into exercise_rest_preferences(owner_name,exercise_id,rest_seconds,version) values(?,?,?,1)
            on conflict(owner_name,exercise_id) do nothing
            """,
            owner,
            id,
            edit.restSeconds()
          )
        : jdbc.update(
            """
            update exercise_rest_preferences set rest_seconds=?,version=version+1
            where owner_name=? and exercise_id=? and version=?
            """,
            edit.restSeconds(),
            owner,
            id,
            edit.version()
          );
    if (changed != 1) throw new WorkoutConflict(
      "종목 휴식 설정이 변경되었습니다. 다시 조회해 주세요."
    );
    return get(id, owner);
  }

  private record Preference(long version, Integer seconds) {}
}
