package com.rufit.social;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 알림과 원래 관계를 함께 조회하여 취소된 신청이나 차단된 상대를 노출하지 않습니다. */
@Service
@Transactional
public class NotificationService {
  private final JdbcTemplate jdbc;
  NotificationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  public record Item(String id, String type, UUID actorId, String actorNickname,
      UUID requestId, Instant createdAt, boolean read) {}
  public record Inbox(List<Item> items, long unreadCount, boolean hasNext) {}

  private static final String VISIBLE = """
      FROM social_notifications n
      JOIN social_directory recipient ON recipient.member_id=n.recipient_id
      JOIN social_directory actor ON actor.member_id=n.actor_id
      JOIN friend_requests r ON r.id=n.request_id
      WHERE recipient.owner_name=?
      AND NOT EXISTS (SELECT 1 FROM social_blocks b WHERE
        (b.blocker_id=n.recipient_id AND b.blocked_id=n.actor_id) OR
        (b.blocker_id=n.actor_id AND b.blocked_id=n.recipient_id))
      AND ((n.type='FRIEND_REQUEST' AND r.state='PENDING') OR
        (n.type='FRIEND_ACCEPTED' AND EXISTS (SELECT 1 FROM friendships f WHERE
          f.first_id=least(n.recipient_id,n.actor_id) AND f.second_id=greatest(n.recipient_id,n.actor_id))))
      """;

  @Transactional(readOnly=true)
  public Inbox inbox(String owner, Long before) {
    if (before != null && before <= 0) throw new IllegalArgumentException("알림 커서가 올바르지 않습니다.");
    var args = new ArrayList<Object>(); args.add(owner);
    String cursor = before == null ? "" : " AND n.id < ?";
    if (before != null) args.add(before);
    var rows = jdbc.query("SELECT n.*,actor.nickname " + VISIBLE + cursor + " ORDER BY n.id DESC LIMIT 31",
        (r,i) -> new Item(r.getString("id"),r.getString("type"),r.getObject("actor_id",UUID.class),
          r.getString("nickname"),r.getObject("request_id",UUID.class),r.getTimestamp("created_at").toInstant(),
          r.getTimestamp("read_at") != null), args.toArray());
    long unread = Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) " + VISIBLE + " AND n.read_at IS NULL",Long.class,owner));
    return new Inbox(List.copyOf(rows.subList(0,Math.min(30,rows.size()))),unread,rows.size()>30);
  }

  /** 소유권과 현재 노출 가능 여부를 모두 확인하며 읽음 재시도는 같은 결과를 유지합니다. */
  public void read(String owner, long id) {
    int changed = jdbc.update("UPDATE social_notifications SET read_at=COALESCE(read_at,now()) WHERE id IN (SELECT n.id " + VISIBLE + " AND n.id=?)",owner,id);
    if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"알림을 찾을 수 없습니다.");
  }
}
