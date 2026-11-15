package com.rufit.exercise;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ExerciseFavoritesService {

  /** 한 계정이 담을 수 있는 즐겨찾기 수입니다. 종목 목록(공용 110종 + 개인 종목)보다 넉넉하게 둡니다. */
  static final int LIMIT = 500;

  private final JdbcTemplate jdbc;
  private final ExerciseService exercises;
  private final Clock clock;

  ExerciseFavoritesService(JdbcTemplate jdbc, ExerciseService exercises, ObjectProvider<Clock> clock) {
    this.jdbc = jdbc;
    this.exercises = exercises;
    // 시계 빈은 소셜 로그인 설정에서만 만들어지므로, 없으면 UTC 시스템 시계를 씁니다.
    this.clock = clock.getIfAvailable(Clock::systemUTC);
  }

  @Transactional(readOnly = true)
  public List<ExerciseFavoritesController.Favorite> list(String owner) {
    // 지금 볼 수 없는 종목(다른 사람 종목)은 목록에 남기지 않습니다.
    return jdbc.query(
      """
      select f.exercise_id,f.created_at from exercise_favorites f join exercises e on e.id=f.exercise_id
      where f.owner_name=? and (e.owner_name is null or e.owner_name=?)
      order by f.created_at desc, f.exercise_id
      """,
      (rs, row) ->
        new ExerciseFavoritesController.Favorite(
          rs.getObject(1, UUID.class),
          rs.getTimestamp(2).toInstant()
        ),
      owner,
      owner
    );
  }

  public ExerciseFavoritesController.Favorite add(UUID id, String owner) {
    exercises.get(id, owner);
    var existing = find(id, owner);
    if (existing != null) return existing;
    Long count = jdbc.queryForObject("select count(*) from exercise_favorites where owner_name=?", Long.class, owner);
    if (count != null && count >= LIMIT) throw new IllegalArgumentException(
      "즐겨찾기는 " + LIMIT + "개까지 담을 수 있습니다."
    );
    jdbc.update(
      "insert into exercise_favorites(owner_name,exercise_id,created_at) values(?,?,?) on conflict do nothing",
      owner,
      id,
      Timestamp.from(clock.instant())
    );
    return find(id, owner);
  }

  public void remove(UUID id, String owner) {
    jdbc.update("delete from exercise_favorites where owner_name=? and exercise_id=?", owner, id);
  }

  private ExerciseFavoritesController.Favorite find(UUID id, String owner) {
    return jdbc
      .query(
        "select exercise_id,created_at from exercise_favorites where owner_name=? and exercise_id=?",
        (rs, row) ->
          new ExerciseFavoritesController.Favorite(
            rs.getObject(1, UUID.class),
            rs.getTimestamp(2).toInstant()
          ),
        owner,
        id
      )
      .stream()
      .findFirst()
      .orElse(null);
  }
}
