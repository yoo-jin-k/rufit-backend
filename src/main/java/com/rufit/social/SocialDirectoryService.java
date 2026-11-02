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
  SocialDirectoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Transactional(readOnly = true)
  public Settings get(String owner) {
    return read(owner, false).orElse(new Settings(null, null, false, 0));
  }

  private Optional<Settings> read(String owner, boolean lock) {
    return jdbc.query("SELECT member_id,nickname,discoverable,version FROM social_directory WHERE owner_name=?"
        + (lock ? " FOR UPDATE" : ""),
        (rs, index) -> new Settings(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3), rs.getLong(4)), owner)
        .stream().findFirst();
  }

  /** 최초 동시 생성도 계정별로 직렬화하고 응답 유실 재시도에서 버전을 중복 증가시키지 않습니다. */
  public Settings update(String owner, Edit edit) {
    String nickname = edit.nickname().trim();
    if (nickname.length() < 2 || nickname.length() > 30) throw new IllegalArgumentException("닉네임은 2~30자로 입력해 주세요.");
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, "social-directory:" + owner);
    var current = read(owner, true);
    if (current.isPresent()) {
      Settings value = current.get();
      if (value.nickname().equals(nickname) && value.discoverable() == edit.discoverable()) return value;
      if (value.version() != edit.version()) throw new WorkoutConflict("검색 설정이 변경되었습니다. 다시 조회해 주세요.");
      jdbc.update("UPDATE social_directory SET nickname=?,discoverable=?,version=version+1 WHERE owner_name=?",
          nickname, edit.discoverable(), owner);
    } else {
      if (edit.version() != 0) throw new WorkoutConflict("검색 설정을 다시 조회해 주세요.");
      jdbc.update("INSERT INTO social_directory(owner_name,member_id,nickname,discoverable) VALUES (?,?,?,?)",
          owner, UUID.randomUUID(), nickname, edit.discoverable());
    }
    return read(owner, false).orElseThrow();
  }

  /** 와일드카드를 이스케이프하고 공개 동의한 최소 정보만 제한된 페이지로 반환합니다. */
  @Transactional(readOnly = true)
  public Results search(String owner, String query, int page, int size) {
    String q = query.trim();
    if (q.length() < 2 || q.length() > 30 || page < 0 || page > 1000 || size < 1 || size > 50)
      throw new IllegalArgumentException("검색어는 2~30자, 페이지와 조회 개수는 허용 범위로 입력해 주세요.");
    String pattern = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    var rows = jdbc.query("SELECT member_id,nickname FROM social_directory WHERE discoverable=true AND owner_name<>?"
        + " AND lower(nickname) LIKE lower(?) ESCAPE '!' ORDER BY lower(nickname),member_id LIMIT ? OFFSET ?",
        (rs, index) -> new Member(rs.getObject(1, UUID.class), rs.getString(2)), owner, pattern, size + 1, page * size);
    return new Results(List.copyOf(rows.subList(0, Math.min(size, rows.size()))), page, size, rows.size() > size);
  }
}
