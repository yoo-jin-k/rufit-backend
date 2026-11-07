package com.rufit.auth;

import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 양쪽 인증이 끝난 요청만 호출합니다. 충돌 시 트랜잭션 전체를 되돌려 실제 기록을 보존합니다. */
@Component
@Profile("social")
public class AccountMerge {
  private final JdbcTemplate jdbc;
  public AccountMerge(JdbcTemplate jdbc) { this.jdbc=jdbc; }

  /** 드문 계정 통합 동안 쓰기를 직렬화하여 다른 요청과 부분 이전이 섞이지 않도록 합니다. */
  void lock() {
    jdbc.execute("lock table auth_accounts,auth_identities,auth_sessions,auth_email_credentials,auth_login_attempts,workouts,routines,retired_workout_keys,retired_routine_keys,exercises,measurements,user_profiles,profile_goals,rest_preferences,exercise_rest_preferences,social_directory,friend_requests,friendships,social_blocks,social_reports,social_notifications,journal_posts,journal_photos,journal_likes,social_live_settings,social_live_workouts,social_cheers in share row exclusive mode");
  }

  void merge(UUID target, UUID source, UUID retainedSession) {
    if (target.equals(source)) return;
    var owners=jdbc.query("select id,owner_key from auth_accounts where id in (?,?) order by id for update",
      (r,n)->Map.entry(r.getObject(1,UUID.class),r.getString(2)),target,source);
    String to=owners.stream().filter(e->e.getKey().equals(target)).findFirst().orElseThrow(AuthFailure::proof).getValue();
    String from=owners.stream().filter(e->e.getKey().equals(source)).findFirst().orElseThrow(AuthFailure::proof).getValue();
    if (exists("select exists(select 1 from auth_identities a join auth_identities b on a.provider=b.provider where a.account_id=? and b.account_id=?)",target,source)
      || exists("select exists(select 1 from auth_email_credentials a,auth_email_credentials b where a.account_id=? and b.account_id=?)",target,source)) throw conflict();
    // 프로필과 설정은 현재 계정을 우선하되 이전 값을 감사 기록에 보존합니다.
    jdbc.update("insert into auth_account_merges(source_account,target_account,snapshot) values(?,?,jsonb_build_object('profiles',(select jsonb_agg(to_jsonb(p)) from user_profiles p where owner_name in (?,?)),'goals',(select jsonb_agg(to_jsonb(p)) from profile_goals p where owner_name in (?,?)),'rest',(select jsonb_agg(to_jsonb(p)) from rest_preferences p where owner_name in (?,?)),'exerciseRest',(select jsonb_agg(to_jsonb(p)) from exercise_rest_preferences p where owner_name in (?,?)),'directory',(select jsonb_agg(to_jsonb(p)) from social_directory p where owner_name in (?,?)),'live',(select jsonb_agg(to_jsonb(p)) from social_live_settings p where member_id in (select member_id from social_directory where owner_name in (?,?)))))",
      source,target,from,to,from,to,from,to,from,to,from,to,from,to);
    for (String table: List.of("workouts","routines","retired_workout_keys","retired_routine_keys","exercises","measurements"))
      jdbc.update("update "+table+" set owner_name=? where owner_name=?",to,from);
    if (!exists("select exists(select 1 from user_profiles where owner_name=?)",to)) {
      jdbc.update("insert into user_profiles select ?,creation_key,nickname,bio,experience,version from user_profiles where owner_name=?",to,from);
    }
    jdbc.update("insert into profile_goals select ?,goal from profile_goals where owner_name=? on conflict do nothing",to,from);
    jdbc.update("delete from user_profiles where owner_name=?",from);
    jdbc.update("insert into rest_preferences select ?,default_rest_seconds,version from rest_preferences where owner_name=? on conflict do nothing",to,from);
    jdbc.update("delete from rest_preferences where owner_name=?",from);
    jdbc.update("insert into exercise_rest_preferences select ?,exercise_id,rest_seconds,version from exercise_rest_preferences where owner_name=? on conflict do nothing",to,from);
    jdbc.update("delete from exercise_rest_preferences where owner_name=?",from);
    UUID src=member(from),dst=member(to);
    if (src!=null && dst==null) jdbc.update("update social_directory set owner_name=? where owner_name=?",to,from);
    else if (src!=null && dst!=null) mergeSocial(src,dst);
    jdbc.update("update auth_identities set account_id=? where account_id=?",target,source);
    jdbc.update("update auth_email_credentials set account_id=? where account_id=?",target,source);
    jdbc.update("update auth_sessions set revoked_at=coalesce(revoked_at,now()) where account_id in (?,?) and id<>?",target,source,retainedSession);
  }

  private void mergeSocial(UUID from,UUID to) {
    // 게시물 ID와 사진 FK는 바꾸지 않습니다. 중복 원본 키는 덮어쓰지 않고 통합을 중단합니다.
    jdbc.update("update journal_posts set member_id=? where member_id=?",to,from);
    jdbc.update("insert into journal_likes select post_id,? from journal_likes where member_id=? on conflict do nothing",to,from);
    jdbc.update("delete from journal_likes where member_id=?",from);
    jdbc.update("insert into friendships select least(case when first_id=? then ? else first_id end,case when second_id=? then ? else second_id end),greatest(case when first_id=? then ? else first_id end,case when second_id=? then ? else second_id end) from friendships where first_id=? or second_id=? on conflict do nothing",from,to,from,to,from,to,from,to,from,from);
    jdbc.update("delete from friendships where first_id=? or second_id=?",from,from);
    jdbc.update("insert into social_blocks select case when blocker_id=? then ? else blocker_id end,case when blocked_id=? then ? else blocked_id end from social_blocks where blocker_id=? or blocked_id=? on conflict do nothing",from,to,from,to,from,from);
    jdbc.update("delete from social_blocks where blocker_id=? or blocked_id=?",from,from);
    for (var relation: List.of(new String[]{"friend_requests","requester_id","target_id"},new String[]{"social_reports","reporter_id","target_id"},new String[]{"social_notifications","recipient_id","actor_id"},new String[]{"social_cheers","recipient_id","actor_id"})) {
      for(int i=1;i<3;i++) jdbc.update("update "+relation[0]+" set "+relation[i]+"=? where "+relation[i]+"=?",to,from);
    }
    // 공개 상태는 명시적인 재설정 전 비공개로 둡니다. 진행 중 공개 운동은 종료 후 재시도합니다.
    if (exists("select exists(select 1 from social_live_workouts where member_id in (?,?) and updated_at>now()-interval '2 minutes')",from,to)) throw conflict();
    jdbc.update("delete from social_live_workouts where member_id in (?,?)",from,to);
    jdbc.update("insert into social_live_settings(member_id,sharing) values(?,false) on conflict(member_id) do update set sharing=false",to);
    jdbc.update("delete from social_live_settings where member_id=?",from);
    jdbc.update("delete from social_directory where member_id=?",from);
  }
  private UUID member(String owner) {
    var rows=jdbc.query("select member_id from social_directory where owner_name=?",(r,n)->r.getObject(1,UUID.class),owner);
    return rows.isEmpty()?null:rows.getFirst();
  }
  private boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
  static AuthFailure conflict() { return new AuthFailure(409,"ACCOUNT_MERGE_CONFLICT"); }
}
