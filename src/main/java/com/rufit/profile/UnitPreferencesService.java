package com.rufit.profile;

import com.rufit.workout.WorkoutConflict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 휴식 설정과 같은 방식으로 버전을 비교해 다른 기기의 변경을 덮어쓰지 않습니다. */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class UnitPreferencesService {

  private final JdbcTemplate jdbc;

  UnitPreferencesService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public UnitPreferencesController.View get(String owner) {
    return jdbc
      .query(
        "select version,weight_unit from unit_preferences where owner_name=?",
        (rs, row) -> new UnitPreferencesController.View(rs.getLong(1), rs.getString(2)),
        owner
      )
      .stream()
      .findFirst()
      .orElse(new UnitPreferencesController.View(0, "KG"));
  }

  public UnitPreferencesController.View save(String owner, UnitPreferencesController.Edit edit) {
    if (
      edit.version() == null ||
      edit.version() < 0 ||
      !("KG".equals(edit.weightUnit()) || "LB".equals(edit.weightUnit()))
    ) throw new IllegalArgumentException("단위는 KG 또는 LB, 버전은 0 이상이어야 합니다.");
    int changed = edit.version() == 0
      ? jdbc.update(
        "insert into unit_preferences(owner_name,weight_unit,version) values(?,?,1) on conflict(owner_name) do nothing",
        owner,
        edit.weightUnit()
      )
      : jdbc.update(
        "update unit_preferences set weight_unit=?,version=version+1 where owner_name=? and version=?",
        edit.weightUnit(),
        owner,
        edit.version()
      );
    if (changed != 1) throw new WorkoutConflict("단위 설정이 변경되었습니다. 다시 조회해 주세요.");
    return get(owner);
  }
}
