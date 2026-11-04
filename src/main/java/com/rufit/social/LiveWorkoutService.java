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

  public record Member(UUID memberId, String nickname, String workoutId, boolean sent) {}

  public record Sender(UUID memberId, String nickname) {}

  public record Snapshot(
    boolean sharing,
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

  @Transactional(readOnly = true)
  public Snapshot snapshot(String owner) {
    UUID me = member(owner);
    boolean sharing =
      count("SELECT count(*) FROM social_live_settings WHERE member_id=? AND sharing", me) > 0;
    var items = jdbc.query(
      "SELECT d.member_id,d.nickname,l.workout_id,EXISTS(SELECT 1 FROM social_cheers c WHERE c.recipient_id=d.member_id AND c.actor_id=? AND c.workout_id=l.workout_id) AS sent FROM social_directory d JOIN social_live_workouts l USING(member_id) JOIN social_live_settings s USING(member_id) WHERE s.sharing AND l.updated_at>now()-interval '120 seconds' AND " +
        FRIEND +
        " ORDER BY d.nickname,d.member_id LIMIT 100",
      (r, i) ->
        new Member(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getBoolean(4)),
      me,
      me,
      me,
      me,
      me
    );
    var received = jdbc.query(
      "SELECT d.member_id,d.nickname FROM social_cheers c JOIN social_directory d ON d.member_id=c.actor_id JOIN social_live_workouts l ON l.member_id=c.recipient_id AND l.workout_id=c.workout_id WHERE c.recipient_id=? AND l.updated_at>now()-interval '120 seconds' AND " +
        FRIEND +
        " ORDER BY c.created_at,d.member_id LIMIT 100",
      (r, i) -> new Sender(r.getObject(1, UUID.class), r.getString(2)),
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
    return new Snapshot(sharing, workoutId, items, received);
  }

  public void sharing(String owner, boolean enabled) {
    UUID me = member(owner);
    lock(me);
    jdbc.update(
      "INSERT INTO social_live_settings(member_id,sharing) VALUES (?,?) ON CONFLICT(member_id) DO UPDATE SET sharing=excluded.sharing",
      me,
      enabled
    );
    if (!enabled) jdbc.update("DELETE FROM social_live_workouts WHERE member_id=?", me);
  }

  public void heartbeat(String owner, String workout, boolean active) {
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
      "INSERT INTO social_live_workouts(member_id,workout_id) VALUES (?,?) ON CONFLICT(member_id) DO UPDATE SET workout_id=excluded.workout_id,updated_at=now()",
      me,
      workout
    );
  }

  public void cheer(String owner, UUID target, String workout) {
    valid(workout);
    UUID me = member(owner);
    if (me.equals(target)) throw new IllegalArgumentException("자신에게 응원을 보낼 수 없습니다.");
    lock(me, target);
    if (
      count(
        "SELECT count(*) FROM social_directory d WHERE d.member_id=? AND " + FRIEND,
        target,
        me,
        me,
        me,
        me
      ) ==
      0
    ) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "응원할 친구를 찾을 수 없습니다.");
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
}
