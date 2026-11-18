package com.rufit.social;

import com.rufit.workout.WorkoutConflict;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 상태 공개와 응원 권한을 서버에서 확인합니다. 클라이언트 시각이나 발신자 ID를 신뢰하지 않습니다. */
@Service
@Transactional
public class LiveWorkoutService {

  private final JdbcTemplate jdbc;

  LiveWorkoutService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** friend가 false면 친구는 아니지만 모두 공개를 고른 회원입니다. */
  public record Member(
    UUID memberId,
    String nickname,
    String workoutId,
    boolean sent,
    boolean friend,
    double volume,
    String avatar
  ) {}

  public record Sender(UUID memberId, String nickname) {}

  public record Snapshot(
    boolean sharing,
    LiveWorkoutController.Scope scope,
    String workoutId,
    List<Member> items,
    List<Sender> received
  ) {}

  private UUID member(String owner) {
    return jdbc
      .query(
        "SELECT member_id FROM social_directory WHERE owner_name=?",
        (r, i) -> r.getObject(1, UUID.class),
        owner
      )
      .stream()
      .findFirst()
      .orElseThrow(() ->
        new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "친구 화면에서 닉네임을 먼저 저장해 주세요."
        )
      );
  }

  private long count(String sql, Object... args) {
    return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
  }

  private void valid(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,120}")) throw new IllegalArgumentException(
      "운동 번호가 올바르지 않습니다."
    );
  }

  private void lock(UUID... ids) {
    Arrays.stream(ids)
      .distinct()
      .sorted(Comparator.comparing(UUID::toString))
      .forEach(id ->
        jdbc.query(
          "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
          r -> {},
          "social-member:" + id
        )
      );
  }

  private static final String FRIEND = """
    EXISTS(SELECT 1 FROM friendships f WHERE f.first_id=least(?,d.member_id) AND f.second_id=greatest(?,d.member_id))
    AND NOT EXISTS(SELECT 1 FROM social_blocks b WHERE (b.blocker_id=? AND b.blocked_id=d.member_id) OR (b.blocked_id=? AND b.blocker_id=d.member_id))
    """;

  /** 운동 상태를 볼 수 있는 상대: 차단이 없고, 친구이거나 상대가 모두 공개(s.scope)를 골랐을 때. 인자 5개(me). */
  private static final String VISIBLE = """
    d.member_id<>? AND NOT EXISTS(SELECT 1 FROM social_blocks b WHERE (b.blocker_id=? AND b.blocked_id=d.member_id) OR (b.blocked_id=? AND b.blocker_id=d.member_id))
    AND (s.scope='PUBLIC' OR EXISTS(SELECT 1 FROM friendships f WHERE f.first_id=least(?,d.member_id) AND f.second_id=greatest(?,d.member_id)))
    """;

  /** 내가 공개 중일 때 응원을 보낸 상대: 차단이 없고, 친구이거나 내가 모두 공개일 때. 인자 5개(me). */
  private static final String SENDER = """
    NOT EXISTS(SELECT 1 FROM social_blocks b WHERE (b.blocker_id=? AND b.blocked_id=d.member_id) OR (b.blocked_id=? AND b.blocker_id=d.member_id))
    AND (EXISTS(SELECT 1 FROM social_live_settings mine WHERE mine.member_id=? AND mine.scope='PUBLIC')
      OR EXISTS(SELECT 1 FROM friendships f WHERE f.first_id=least(?,d.member_id) AND f.second_id=greatest(?,d.member_id)))
    """;

  @Transactional(readOnly = true)
  public Snapshot snapshot(String owner) {
    UUID me = member(owner);
    var setting = jdbc
      .query(
        "SELECT sharing,scope FROM social_live_settings WHERE member_id=?",
        (r, i) -> Map.entry(r.getBoolean(1), LiveWorkoutController.Scope.valueOf(r.getString(2))),
        me
      )
      .stream()
      .findFirst()
      .orElse(Map.entry(false, LiveWorkoutController.Scope.FRIENDS));
    // 친구를 먼저 모으고, 같은 묶음 안에서는 지금 운동 볼륨이 큰 순서로 보여 줍니다.
    var items = jdbc.query(
      "SELECT d.member_id,d.nickname,l.workout_id,EXISTS(SELECT 1 FROM social_cheers c WHERE c.recipient_id=d.member_id AND c.actor_id=? AND c.workout_id=l.workout_id) AS sent," +
        " EXISTS(SELECT 1 FROM friendships f WHERE f.first_id=least(?,d.member_id) AND f.second_id=greatest(?,d.member_id)) AS friend,l.volume,p.avatar" +
        " FROM social_directory d JOIN social_live_workouts l USING(member_id) JOIN social_live_settings s USING(member_id) LEFT JOIN social_profiles p USING(member_id)" +
        " WHERE s.sharing AND l.updated_at>now()-interval '120 seconds' AND " +
        VISIBLE +
        " ORDER BY friend DESC,l.volume DESC,d.nickname,d.member_id LIMIT 100",
      (r, i) ->
        new Member(
          r.getObject(1, UUID.class),
          r.getString(2),
          r.getString(3),
          r.getBoolean(4),
          r.getBoolean(5),
          r.getDouble(6),
          JournalImages.uri(r.getBytes(7))
        ),
      me,
      me,
      me,
      me,
      me,
      me,
      me,
      me
    );
    var received = jdbc.query(
      "SELECT d.member_id,d.nickname FROM social_cheers c JOIN social_directory d ON d.member_id=c.actor_id JOIN social_live_workouts l ON l.member_id=c.recipient_id AND l.workout_id=c.workout_id WHERE c.recipient_id=? AND l.updated_at>now()-interval '120 seconds' AND " +
        SENDER +
        " ORDER BY c.created_at,d.member_id LIMIT 100",
      (r, i) -> new Sender(r.getObject(1, UUID.class), r.getString(2)),
      me,
      me,
      me,
      me,
      me,
      me
    );
    String workoutId = jdbc
      .query(
        "SELECT workout_id FROM social_live_workouts WHERE member_id=? AND updated_at>now()-interval '120 seconds'",
        (r, i) -> r.getString(1),
        me
      )
      .stream()
      .findFirst()
      .orElse(null);
    return new Snapshot(setting.getKey(), setting.getValue(), workoutId, items, received);
  }

  /** scope가 null이면 지금 범위를 유지합니다(처음이면 FRIENDS). */
  public void sharing(String owner, boolean enabled, LiveWorkoutController.Scope scope) {
    UUID me = member(owner);
    lock(me);
    jdbc.update(
      "INSERT INTO social_live_settings(member_id,sharing,scope) VALUES (?,?,COALESCE(?,'FRIENDS')) ON CONFLICT(member_id) DO UPDATE SET sharing=excluded.sharing,scope=COALESCE(?,social_live_settings.scope)",
      me,
      enabled,
      scope == null ? null : scope.name(),
      scope == null ? null : scope.name()
    );
    if (!enabled) jdbc.update("DELETE FROM social_live_workouts WHERE member_id=?", me);
  }

  public void heartbeat(String owner, String workout, boolean active, Double volume) {
    valid(workout);
    UUID me = member(owner);
    lock(me);
    if (!active) {
      // 이전 화면의 종료 요청이 새로 시작한 운동을 지우지 않도록 운동 번호도 확인합니다.
      jdbc.update(
        "DELETE FROM social_live_workouts WHERE member_id=? AND workout_id=?",
        me,
        workout
      );
      return;
    }
    if (
      count("SELECT count(*) FROM social_live_settings WHERE member_id=? AND sharing", me) == 0
    ) throw new WorkoutConflict("운동 중 상태 공개가 꺼져 있습니다.");
    jdbc.update(
      // 다른 운동으로 바뀌면 볼륨을 새로 시작합니다. volume을 생략한 이전 앱은 값을 유지합니다.
      "INSERT INTO social_live_workouts(member_id,workout_id,volume) VALUES (?,?,COALESCE(?,0)) ON CONFLICT(member_id) DO UPDATE SET" +
        " volume=COALESCE(?,CASE WHEN social_live_workouts.workout_id=excluded.workout_id THEN social_live_workouts.volume ELSE 0 END)," +
        "workout_id=excluded.workout_id,updated_at=now()",
      me,
      workout,
      volume,
      volume
    );
  }

  public void cheer(String owner, UUID target, String workout) {
    valid(workout);
    UUID me = member(owner);
    if (me.equals(target)) throw new IllegalArgumentException("자신에게 응원을 보낼 수 없습니다.");
    lock(me, target);
    if (
      count(
        "SELECT count(*) FROM social_directory d LEFT JOIN social_live_settings s USING(member_id) WHERE d.member_id=? AND (" +
          FRIEND +
          " OR (s.scope='PUBLIC' AND NOT EXISTS(SELECT 1 FROM social_blocks b WHERE (b.blocker_id=? AND b.blocked_id=d.member_id) OR (b.blocked_id=? AND b.blocker_id=d.member_id))))",
        target,
        me,
        me,
        me,
        me,
        me,
        me
      ) ==
      0
    ) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "응원할 회원을 찾을 수 없습니다.");
    // 성공 응답을 잃은 재시도는 운동 종료 후에도 같은 성공으로 처리합니다.
    if (
      count(
        "SELECT count(*) FROM social_cheers WHERE recipient_id=? AND actor_id=? AND workout_id=?",
        target,
        me,
        workout
      ) >
      0
    ) return;
    if (
      count(
        "SELECT count(*) FROM social_live_workouts l JOIN social_live_settings s USING(member_id) WHERE l.member_id=? AND l.workout_id=? AND s.sharing AND l.updated_at>now()-interval '120 seconds'",
        target,
        workout
      ) ==
      0
    ) throw new WorkoutConflict("운동이 종료되었거나 상태가 만료되었습니다.");
    jdbc.update(
      "INSERT INTO social_cheers(recipient_id,actor_id,workout_id) VALUES (?,?,?)",
      target,
      me,
      workout
    );
    jdbc.update(
      "INSERT INTO social_notifications(recipient_id,actor_id,workout_id,type) VALUES (?,?,?,'WORKOUT_CHEER') ON CONFLICT DO NOTHING",
      target,
      me,
      workout
    );
  }

  /** 내가 보낸 응원만 지웁니다. 응원 기록과 같은 운동의 응원 알림을 같은 트랜잭션에서 지워 재시도해도 결과가 같습니다. */
  public void cancelCheer(String owner, UUID target, String workout) {
    valid(workout);
    UUID me = member(owner);
    lock(me, target);
    jdbc.update(
      "DELETE FROM social_cheers WHERE recipient_id=? AND actor_id=? AND workout_id=?",
      target,
      me,
      workout
    );
    jdbc.update(
      "DELETE FROM social_notifications WHERE type='WORKOUT_CHEER' AND recipient_id=? AND actor_id=? AND workout_id=?",
      target,
      me,
      workout
    );
  }
}
