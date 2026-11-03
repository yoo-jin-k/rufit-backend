package com.rufit.social;

import static com.rufit.social.FriendController.*;
import com.rufit.workout.WorkoutConflict;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class FriendService {
  private final JdbcTemplate jdbc;
  FriendService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."); }
  private UUID member(String owner) {
    return jdbc.query("SELECT member_id FROM social_directory WHERE owner_name=?", (r,i)->r.getObject(1,UUID.class),owner)
        .stream().findFirst().orElseThrow(this::missing);
  }
  // 두 회원의 잠금을 같은 순서로 획득하여 교차 신청과 친구 수 제한을 함께 보호합니다.
  private List<UUID> lock(UUID a, UUID b) {
    var pair = new ArrayList<>(List.of(a,b));
    pair.sort(Comparator.comparing(UUID::toString));
    for (UUID id : pair) jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", r -> {}, "social-member:"+id);
    return pair;
  }
  private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args)); }
  private boolean blocked(UUID a, UUID b) {
    return count("SELECT count(*) FROM social_blocks WHERE (blocker_id=? AND blocked_id=?) OR (blocker_id=? AND blocked_id=?)",a,b,b,a)>0;
  }
  private Optional<Request> find(UUID id) {
    return jdbc.query("SELECT r.id,r.requester_id,r.target_id,r.state,a.nickname,b.nickname FROM friend_requests r JOIN social_directory a ON a.member_id=r.requester_id JOIN social_directory b ON b.member_id=r.target_id WHERE r.id=?",
        (r,i)->new Request(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),r.getString(5),r.getString(6)),id).stream().findFirst();
  }
  private long friendCount(UUID id) { return count("SELECT count(*) FROM friendships WHERE first_id=? OR second_id=?",id,id); }
  private long pendingCount(UUID id) { return count("SELECT count(*) FROM friend_requests WHERE state='PENDING' AND (requester_id=? OR target_id=?)",id,id); }

  public Request send(String owner, Send body) {
    UUID me = member(owner), target = body.targetId();
    if (me.equals(target)) throw new WorkoutConflict("자신에게 친구 신청을 보낼 수 없습니다.");
    var pair = lock(me,target);
    var previous = find(body.requestId());
    if (previous.isPresent()) {
      Request old = previous.get();
      if (!old.requesterId().equals(me) || !old.targetId().equals(target)) throw new WorkoutConflict("다른 신청에 사용한 요청 번호입니다.");
      return old;
    }
    if (blocked(me,target) || count("SELECT count(*) FROM social_directory WHERE member_id=? AND discoverable=true",target)==0) throw missing();
    if (count("SELECT count(*) FROM friendships WHERE first_id=? AND second_id=?",pair.get(0),pair.get(1))>0)
      throw new WorkoutConflict("이미 친구입니다.");
    if (pendingCount(me)>=100 || pendingCount(target)>=100) throw new WorkoutConflict("대기 중인 신청을 먼저 정리해 주세요.");
    if (count("SELECT count(*) FROM friend_requests WHERE state='PENDING' AND ((requester_id=? AND target_id=?) OR (requester_id=? AND target_id=?))",me,target,target,me)>0)
      throw new WorkoutConflict("이미 대기 중인 친구 신청이 있습니다.");
    jdbc.update("INSERT INTO friend_requests(id,requester_id,target_id,state) VALUES (?,?,?,'PENDING')",body.requestId(),me,target);
    return find(body.requestId()).orElseThrow();
  }

  public Request respond(String owner, UUID id, Action action) {
    UUID me=member(owner);
    Request initial=find(id).orElseThrow(this::missing);
    UUID actor=action==Action.CANCEL ? initial.requesterId() : initial.targetId();
    if (!me.equals(actor)) throw missing();
    var pair=lock(initial.requesterId(),initial.targetId());
    Request current=find(id).orElseThrow(this::missing);
    String desired=switch(action) { case ACCEPT -> "ACCEPTED"; case REJECT -> "REJECTED"; case CANCEL -> "CANCELLED"; };
    // 종료된 신청은 재시도로 상태를 바꾸거나 차단으로 제거된 친구를 되살리지 않습니다.
    if (current.state().equals(desired)) return current;
    if (!current.state().equals("PENDING")) throw new WorkoutConflict("이미 처리된 친구 신청입니다.");
    if (blocked(current.requesterId(),current.targetId())) throw missing();
    if (action==Action.ACCEPT) {
      if (friendCount(pair.get(0))>=100 || friendCount(pair.get(1))>=100) throw new WorkoutConflict("친구는 최대 100명까지 연결할 수 있습니다.");
      jdbc.update("INSERT INTO friendships(first_id,second_id) VALUES (?,?) ON CONFLICT DO NOTHING",pair.get(0),pair.get(1));
    }
    jdbc.update("UPDATE friend_requests SET state=? WHERE id=?",desired,id);
    return find(id).orElseThrow();
  }

  @Transactional(readOnly=true)
  public Requests requests(String owner) {
    UUID me=member(owner);
    var list=jdbc.query("SELECT r.id,r.requester_id,r.target_id,r.state,a.nickname,b.nickname FROM friend_requests r JOIN social_directory a ON a.member_id=r.requester_id JOIN social_directory b ON b.member_id=r.target_id WHERE r.state='PENDING' AND (r.requester_id=? OR r.target_id=?) ORDER BY r.created_at,r.id LIMIT 100",
        (r,i)->new Request(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),r.getString(5),r.getString(6)),me,me);
    return new Requests(list.stream().filter(r->r.targetId().equals(me)).toList(),list.stream().filter(r->r.requesterId().equals(me)).toList());
  }
  @Transactional(readOnly=true)
  public Members friends(String owner) {
    UUID me=member(owner);
    return new Members(jdbc.query("SELECT d.member_id,d.nickname FROM social_directory d JOIN friendships f ON (f.first_id=? AND f.second_id=d.member_id) OR (f.second_id=? AND f.first_id=d.member_id) ORDER BY d.nickname,d.member_id LIMIT 100",
        (r,i)->new SocialDirectoryController.Member(r.getObject(1,UUID.class),r.getString(2)),me,me));
  }
  @Transactional(readOnly=true)
  public Members blocks(String owner) {
    return new Members(jdbc.query("SELECT d.member_id,d.nickname FROM social_directory d JOIN social_blocks b ON b.blocked_id=d.member_id WHERE b.blocker_id=? ORDER BY d.nickname,d.member_id",
        (r,i)->new SocialDirectoryController.Member(r.getObject(1,UUID.class),r.getString(2)),member(owner)));
  }
  public void block(String owner, UUID target, boolean enabled) {
    UUID me=member(owner);
    if (me.equals(target)) throw new IllegalArgumentException("자신을 차단할 수 없습니다.");
    var pair=lock(me,target);
    if (count("SELECT count(*) FROM social_directory WHERE member_id=?",target)==0) throw missing();
    if (!enabled) { jdbc.update("DELETE FROM social_blocks WHERE blocker_id=? AND blocked_id=?",me,target); return; }
    jdbc.update("INSERT INTO social_blocks(blocker_id,blocked_id) VALUES (?,?) ON CONFLICT DO NOTHING",me,target);
    jdbc.update("DELETE FROM friendships WHERE first_id=? AND second_id=?",pair.get(0),pair.get(1));
    jdbc.update("UPDATE friend_requests SET state='CANCELLED' WHERE state='PENDING' AND ((requester_id=? AND target_id=?) OR (requester_id=? AND target_id=?))",me,target,target,me);
  }

  /** 인증한 본인과 대상 사이의 친구 관계만 제거합니다. */
  public void remove(String owner, UUID target) {
    UUID me=member(owner);
    if (me.equals(target)) throw new IllegalArgumentException("자신을 친구 해제할 수 없습니다.");
    var pair=lock(me,target);
    if (count("SELECT count(*) FROM social_directory WHERE member_id=?",target)==0) throw missing();
    jdbc.update("DELETE FROM friendships WHERE first_id=? AND second_id=?",pair.get(0),pair.get(1));
  }

  /** 공개 동의와 관계를 확인한 뒤 최소 프로필만 반환합니다. 차단 여부는 노출하지 않습니다. */
  @Transactional(readOnly=true)
  public MemberProfile profile(String owner, UUID target) {
    UUID me=member(owner);
    if (blocked(me,target)) throw missing();
    String relation="NONE";
    if (me.equals(target)) relation="SELF";
    else if (count("SELECT count(*) FROM friendships WHERE (first_id=? AND second_id=?) OR (first_id=? AND second_id=?)",me,target,target,me)>0) relation="FRIEND";
    else if (count("SELECT count(*) FROM friend_requests WHERE requester_id=? AND target_id=? AND state='PENDING'",target,me)>0) relation="INCOMING";
    else if (count("SELECT count(*) FROM friend_requests WHERE requester_id=? AND target_id=? AND state='PENDING'",me,target)>0) relation="OUTGOING";
    if (relation.equals("NONE") && count("SELECT count(*) FROM social_directory WHERE member_id=? AND discoverable=true",target)==0) throw missing();
    String name=jdbc.query("SELECT nickname FROM social_directory WHERE member_id=?",(r,n)->r.getString(1),target).stream().findFirst().orElseThrow(this::missing);
    return new MemberProfile(target,name,relation);
  }

  /** 신고번호를 잠가 중복 접수를 방지하며, 신고만으로 친구 관계를 변경하지 않습니다. */
  public Receipt report(String owner, Report body) {
    UUID me=member(owner), target=body.targetId();
    if (me.equals(target)) throw new IllegalArgumentException("자신을 신고할 수 없습니다.");
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", r -> {}, "social-report:"+body.requestId());
    lock(me,target);
    var existing=jdbc.query("SELECT reporter_id,target_id,reason FROM social_reports WHERE id=?",(r,n)->List.of(r.getString(1),r.getString(2),r.getString(3)),body.requestId());
    if (!existing.isEmpty()) {
      if (!existing.getFirst().equals(List.of(me.toString(),target.toString(),body.reason().name()))) throw new WorkoutConflict("다른 신고에 사용한 요청 번호입니다.");
      return new Receipt(body.requestId(),"RECEIVED");
    }
    if (count("SELECT count(*) FROM social_blocks WHERE blocker_id=? AND blocked_id=?",me,target)==0) profile(owner,target);
    if (count("SELECT count(*) FROM social_reports WHERE reporter_id=? AND created_at >= now()-interval '24 hours'",me)>=20) throw new WorkoutConflict("신고 접수 한도를 초과했습니다. 나중에 다시 시도해 주세요.");
    jdbc.update("INSERT INTO social_reports(id,reporter_id,target_id,reason) VALUES (?,?,?,?)",body.requestId(),me,target,body.reason().name());
    return new Receipt(body.requestId(),"RECEIVED");
  }
}
