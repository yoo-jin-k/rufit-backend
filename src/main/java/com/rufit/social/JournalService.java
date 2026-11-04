package com.rufit.social;

import com.rufit.workout.WorkoutConflict;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 게시물·사진·좋아요에 같은 친구/차단 권한을 적용합니다. 운동 원본은 변경하지 않습니다. */
@Service
@Transactional
public class JournalService {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  JournalService(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public record Post(
    UUID id,
    String sourceId,
    UUID memberId,
    String nickname,
    String memo,
    LocalDate date,
    String summary,
    String visibility,
    long version,
    List<String> photos,
    String thumbnail,
    long likes,
    boolean liked,
    boolean mine
  ) {}

  public record Page(List<Post> items, boolean hasNext, long total) {}

  private ResponseStatusException missing() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "게시물을 찾을 수 없습니다.");
  }

  private UUID member(String owner) {
    return jdbc
      .query(
        "SELECT member_id FROM social_directory WHERE owner_name=?",
        (r, n) -> r.getObject(1, UUID.class),
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

  private static final String ALLOWED = """
    (p.member_id=? OR (p.visibility='FRIENDS'
      AND EXISTS(SELECT 1 FROM friendships f WHERE f.first_id=least(p.member_id,?) AND f.second_id=greatest(p.member_id,?))
      AND NOT EXISTS(SELECT 1 FROM social_blocks b WHERE (b.blocker_id=p.member_id AND b.blocked_id=?) OR (b.blocker_id=? AND b.blocked_id=p.member_id))))
    """;

  private Post read(UUID me, UUID id, boolean photos) {
    var rows = jdbc.query(
      "SELECT p.*,d.nickname,(SELECT count(*) FROM journal_likes l WHERE l.post_id=p.id) likes,EXISTS(SELECT 1 FROM journal_likes l WHERE l.post_id=p.id AND l.member_id=?) liked FROM journal_posts p JOIN social_directory d ON d.member_id=p.member_id WHERE p.id=? AND " +
        ALLOWED,
      (r, n) ->
        new Post(
          id,
          me.equals(r.getObject("member_id", UUID.class)) ? r.getString("source_id") : null,
          r.getObject("member_id", UUID.class),
          r.getString("nickname"),
          r.getString("memo"),
          r.getObject("workout_date", LocalDate.class),
          r.getString("summary"),
          r.getString("visibility"),
          r.getLong("version"),
          List.of(),
          JournalImages.uri(r.getBytes("thumbnail")),
          r.getLong("likes"),
          r.getBoolean("liked"),
          me.equals(r.getObject("member_id", UUID.class))
        ),
      me,
      id,
      me,
      me,
      me,
      me,
      me
    );
    Post p = rows.stream().findFirst().orElseThrow(this::missing);
    if (!photos) return p;
    var images = jdbc.query(
      "SELECT content FROM journal_photos WHERE post_id=? ORDER BY position",
      (r, n) -> JournalImages.uri(r.getBytes(1)),
      id
    );
    return new Post(
      p.id,
      p.sourceId,
      p.memberId,
      p.nickname,
      p.memo,
      p.date,
      p.summary,
      p.visibility,
      p.version,
      images,
      p.thumbnail,
      p.likes,
      p.liked,
      p.mine
    );
  }

  @Transactional(readOnly = true)
  public Post get(String owner, UUID id) {
    return read(member(owner), id, true);
  }

  @Transactional(readOnly = true)
  public Post mine(String owner, String source) {
    UUID me = member(owner);
    UUID id = jdbc
      .query(
        "SELECT id FROM journal_posts WHERE member_id=? AND source_id=?",
        (r, n) -> r.getObject(1, UUID.class),
        me,
        source
      )
      .stream()
      .findFirst()
      .orElseThrow(this::missing);
    return read(me, id, true);
  }

  @Transactional(readOnly = true)
  public Page list(String owner, UUID target, int page) {
    if (page < 0 || page > 1000) throw new IllegalArgumentException(
      "페이지 범위가 올바르지 않습니다."
    );
    UUID me = member(owner);
    if (target == null) target = me;
    var ids = jdbc.query(
      "SELECT p.id FROM journal_posts p WHERE p.member_id=? AND " +
        ALLOWED +
        " ORDER BY p.created_at DESC,p.id DESC LIMIT 13 OFFSET ?",
      (r, n) -> r.getObject(1, UUID.class),
      target,
      me,
      me,
      me,
      me,
      me,
      page * 12
    );
    long total = jdbc.queryForObject(
      "SELECT count(*) FROM journal_posts p WHERE p.member_id=? AND " + ALLOWED,
      Long.class,
      target,
      me,
      me,
      me,
      me,
      me
    );
    return new Page(
      ids
        .stream()
        .limit(12)
        .map(id -> read(me, id, false))
        .toList(),
      ids.size() > 12,
      total
    );
  }

  /** 동일 내용 재시도는 중복 저장하지 않고, 다른 변경은 버전을 비교한 뒤 원자적으로 반영합니다. */
  public Post save(String owner, String source, JournalController.Edit edit) {
    if (source.isBlank() || source.length() > 120) throw new IllegalArgumentException(
      "운동 식별자를 확인해 주세요."
    );
    UUID me = member(owner);
    lock(me);
    String hash;
    try {
      hash = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          mapper
            .writeValueAsString(
              List.of(edit.memo(), edit.date(), edit.summary(), edit.visibility(), edit.photos())
            )
            .getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    var existing = jdbc.query(
      "SELECT id,version,content_hash FROM journal_posts WHERE member_id=? AND source_id=?",
      (r, n) -> new Object[] { r.getObject(1, UUID.class), r.getLong(2), r.getString(3) },
      me,
      source
    );
    UUID id = existing.isEmpty() ? UUID.randomUUID() : (UUID) existing.getFirst()[0];
    long version = existing.isEmpty() ? 0 : (Long) existing.getFirst()[1];
    if (!existing.isEmpty() && hash.equals(existing.getFirst()[2])) return read(me, id, true);
    if (edit.version() != version) throw new WorkoutConflict(
      "다른 곳에서 수정된 게시물입니다. 저장본을 다시 열어 주세요."
    );
    if (
      existing.isEmpty() &&
      jdbc.queryForObject("SELECT count(*) FROM journal_posts WHERE member_id=?", Long.class, me) >=
      200
    ) throw new WorkoutConflict("계정 게시물 저장 한도에 도달했습니다.");
    var photos = edit
      .photos()
      .stream()
      .map(p -> JournalImages.normalize(p, 1280))
      .toList();
    byte[] thumbnail = edit.photos().isEmpty()
      ? null
      : JournalImages.normalize(edit.photos().getFirst(), 240);
    jdbc.update(
      "INSERT INTO journal_posts(id,member_id,source_id,memo,workout_date,summary,visibility,version,content_hash,thumbnail) VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(member_id,source_id) DO UPDATE SET memo=excluded.memo,workout_date=excluded.workout_date,summary=excluded.summary,visibility=excluded.visibility,version=excluded.version,content_hash=excluded.content_hash,thumbnail=excluded.thumbnail",
      id,
      me,
      source,
      edit.memo(),
      edit.date(),
      edit.summary(),
      edit.visibility().name(),
      version + 1,
      hash,
      thumbnail
    );
    jdbc.update("DELETE FROM journal_photos WHERE post_id=?", id);
    for (int i = 0; i < photos.size(); i++) jdbc.update(
      "INSERT INTO journal_photos VALUES (?,?,?)",
      id,
      i,
      photos.get(i)
    );
    if (edit.visibility() == JournalController.Visibility.PRIVATE) jdbc.update(
      "DELETE FROM social_notifications WHERE post_id=?",
      id
    );
    return read(me, id, true);
  }

  public Post like(String owner, UUID id, boolean enabled) {
    UUID me = member(owner);
    UUID target = jdbc
      .query(
        "SELECT member_id FROM journal_posts WHERE id=?",
        (r, n) -> r.getObject(1, UUID.class),
        id
      )
      .stream()
      .findFirst()
      .orElseThrow(this::missing);
    lock(me, target);
    Post p = read(me, id, false);
    if (p.mine) throw new IllegalArgumentException("내 게시물에는 좋아요를 누를 수 없습니다.");
    if (enabled) {
      int added = jdbc.update(
        "INSERT INTO journal_likes VALUES (?,?) ON CONFLICT DO NOTHING",
        id,
        me
      );
      if (added > 0) jdbc.update(
        "INSERT INTO social_notifications(recipient_id,actor_id,post_id,type) VALUES (?,?,?,'POST_LIKED') ON CONFLICT DO NOTHING",
        target,
        me,
        id
      );
    } else {
      jdbc.update("DELETE FROM journal_likes WHERE post_id=? AND member_id=?", id, me);
      jdbc.update("DELETE FROM social_notifications WHERE post_id=? AND actor_id=?", id, me);
    }
    return read(me, id, true);
  }

  /** 본인 최신 게시물만 삭제합니다. 연결 사진·좋아요·알림은 FK CASCADE로 함께 제거됩니다. */
  public void delete(String owner, UUID id, long version) {
    if (version < 0) throw new IllegalArgumentException("게시물 버전이 올바르지 않습니다.");
    UUID me = member(owner);
    lock(me);
    var rows = jdbc.query(
      "SELECT member_id,version FROM journal_posts WHERE id=?",
      (r, n) -> new Object[] { r.getObject(1, UUID.class), r.getLong(2) },
      id
    );
    if (rows.isEmpty()) return;
    if (!me.equals(rows.getFirst()[0])) throw missing();
    if (version != (Long) rows.getFirst()[1]) throw new WorkoutConflict(
      "다른 곳에서 수정된 게시물입니다. 최신 내용을 확인한 뒤 삭제해 주세요."
    );
    jdbc.update("DELETE FROM journal_posts WHERE id=? AND member_id=?", id, me);
  }

  public void hide(String owner, UUID id) {
    UUID me = member(owner);
    lock(me);
    if (
      jdbc.update(
        "UPDATE journal_posts SET visibility='PRIVATE',version=version+1,content_hash='' WHERE id=? AND member_id=?",
        id,
        me
      ) ==
      0
    ) throw missing();
    jdbc.update("DELETE FROM social_notifications WHERE post_id=?", id);
  }
}
