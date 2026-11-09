package com.rufit.profile;

import com.rufit.workout.WorkoutConflict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class RestPreferencesService {

  private final JdbcTemplate jdbc;

  RestPreferencesService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public RestPreferencesController.View get(String owner) {
    return jdbc
      .query(
        "select version,default_rest_seconds from rest_preferences where owner_name=?",
        (rs, row) -> new RestPreferencesController.View(rs.getLong(1), rs.getInt(2)),
        owner
      )
      .stream()
      .findFirst()
      .orElse(new RestPreferencesController.View(0, 90));
  }

  public RestPreferencesController.View save(String owner, RestPreferencesController.Edit edit) {
    if (
      edit.version() == null ||
      edit.version() < 0 ||
      edit.defaultRestSeconds() == null ||
      edit.defaultRestSeconds() < 0 ||
      edit.defaultRestSeconds() > 3600
    ) throw new IllegalArgumentException("휴식 시간은 0~3600초, 버전은 0 이상이어야 합니다.");
    int changed;
    if (edit.version() == 0) {
      changed = jdbc.update(
        "insert into rest_preferences(owner_name,default_rest_seconds,version) values(?,?,1) on conflict(owner_name) do nothing",
        owner,
        edit.defaultRestSeconds()
      );
    } else {
      changed = jdbc.update(
        "update rest_preferences set default_rest_seconds=?,version=version+1 where owner_name=? and version=?",
        edit.defaultRestSeconds(),
        owner,
        edit.version()
      );
    }
    if (changed != 1) throw new WorkoutConflict("휴식 설정이 변경되었습니다. 다시 조회해 주세요.");
    return get(owner);
  }
}
