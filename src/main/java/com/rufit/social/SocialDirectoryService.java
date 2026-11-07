package com.rufit.social;

import static com.rufit.social.SocialDirectoryController.*;

import com.rufit.workout.WorkoutConflict;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SocialDirectoryService {

  private final JdbcTemplate jdbc;

  SocialDirectoryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public Settings get(String owner) {
    return read(owner, false).orElse(new Settings(null, null, false, 0));
  }

  private Optional<Settings> read(String owner, boolean lock) {
    return jdbc
      .query(
        "SELECT member_id,nickname,discoverable,version FROM social_directory WHERE owner_name=?" +
          (lock ? " FOR UPDATE" : ""),
        (rs, index) ->
          new Settings(
            rs.getObject(1, UUID.class),
            rs.getString(2),
            rs.getBoolean(3),
            rs.getLong(4)
          ),
        owner
      )
      .stream()
      .findFirst();
  }

  /** 닉네임 길이 규칙을 확인하고 앞뒤 공백을 제거한 값을 돌려줍니다. */
  private static String normalized(String raw) {
    String nickname = raw == null ? "" : raw.trim();
    if (nickname.length() < 2 || nickname.length() > 30) throw new IllegalArgumentException(
      "닉네임은 2~30자로 입력해 주세요."
    );
    return nickname;
  }

  /** 대소문자와 앞뒤 공백을 무시하고, 본인을 제외한 다른 회원이 같은 닉네임을 쓰는지 확인합니다. */
  private boolean taken(String owner, String nickname) {
    return Boolean.TRUE.equals(
      jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM social_directory WHERE lower(trim(nickname))=lower(?) AND owner_name<>?)",
        Boolean.class,
        nickname,
        owner
      )
    );
  }

  /** 프로필 편집 화면에서 입력 중인 닉네임을 사용할 수 있는지 미리 알려 줍니다. 실제 저장 시에도 같은 기준으로 다시 검사합니다. */
  @Transactional(readOnly = true)
  public Availability availability(String owner, String raw) {
    String nickname = normalized(raw);
    return new Availability(nickname, !taken(owner, nickname));
  }

  /** 최초 동시 생성도 계정별로 직렬화하고 응답 유실 재시도에서 버전을 중복 증가시키지 않습니다. */
  public Settings update(String owner, Edit edit) {
    String nickname = normalized(edit.nickname());
    jdbc.query(
      "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
      rs -> {},
      "social-directory:" + owner
    );
    // 같은 닉네임을 동시에 저장하는 두 요청도 순서대로 검사되도록 닉네임 기준 잠금을 함께 겁니다.
    jdbc.query(
      "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
      rs -> {},
      "social-nickname:" + nickname.toLowerCase(java.util.Locale.ROOT)
    );
    if (taken(owner, nickname)) throw new WorkoutConflict(
      "NICKNAME_TAKEN",
      "이미 사용 중인 닉네임입니다. 다른 닉네임을 입력해 주세요."
    );
    var current = read(owner, true);
    if (current.isPresent()) {
      Settings value = current.get();
      if (
        value.nickname().equals(nickname) && value.discoverable() == edit.discoverable()
      ) return value;
      if (value.version() != edit.version()) throw new WorkoutConflict(
        "검색 설정이 변경되었습니다. 다시 조회해 주세요."
      );
      jdbc.update(
        "UPDATE social_directory SET nickname=?,discoverable=?,version=version+1 WHERE owner_name=?",
        nickname,
        edit.discoverable(),
        owner
      );
    } else {
      if (edit.version() != 0) throw new WorkoutConflict("검색 설정을 다시 조회해 주세요.");
      jdbc.update(
        "INSERT INTO social_directory(owner_name,member_id,nickname,discoverable) VALUES (?,?,?,?)",
        owner,
        UUID.randomUUID(),
        nickname,
        edit.discoverable()
      );
    }
    return read(owner, false).orElseThrow();
  }

  /** 와일드카드를 이스케이프하고 공개 동의한 최소 정보만 제한된 페이지로 반환합니다. */
  @Transactional(readOnly = true)
  public Results search(String owner, String query, int page, int size) {
    String q = query.trim();
    if (
      q.length() < 2 || q.length() > 30 || page < 0 || page > 1000 || size < 1 || size > 50
    ) throw new IllegalArgumentException(
      "검색어는 2~30자, 페이지와 조회 개수는 허용 범위로 입력해 주세요."
    );
    String pattern = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    var rows = jdbc.query(
      "SELECT member_id,nickname FROM social_directory d WHERE discoverable=true AND owner_name<>?" +
        " AND NOT EXISTS (SELECT 1 FROM social_blocks b JOIN social_directory me ON me.owner_name=? WHERE (b.blocker_id=me.member_id AND b.blocked_id=d.member_id) OR (b.blocker_id=d.member_id AND b.blocked_id=me.member_id))" +
        " AND lower(nickname) LIKE lower(?) ESCAPE '!' ORDER BY lower(nickname),member_id LIMIT ? OFFSET ?",
      (rs, index) -> new Member(rs.getObject(1, UUID.class), rs.getString(2)),
      owner,
      owner,
      pattern,
      size + 1,
      page * size
    );
    return new Results(
      List.copyOf(rows.subList(0, Math.min(size, rows.size()))),
      page,
      size,
      rows.size() > size
    );
  }
}
