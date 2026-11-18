package com.rufit.social;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JournalTests {

  @Autowired
  JournalService posts;

  @Autowired
  NotificationService notifications;

  @Autowired
  FriendService friends;

  @Autowired
  SocialDirectoryService directory;

  @Autowired
  JdbcTemplate jdbc;

  @Autowired
  MockMvc mvc;

  UUID a, b, c;

  @BeforeEach
  void setup() {
    jdbc.update("DELETE FROM social_directory");
    a = directory
      .update("alice", new SocialDirectoryController.Edit("게시하나", true, 0L))
      .memberId();
    b = directory.update("bob", new SocialDirectoryController.Edit("게시둘", true, 0L)).memberId();
    c = directory
      .update("carol", new SocialDirectoryController.Edit("게시셋", true, 0L))
      .memberId();
  }

  void connect() {
    UUID id = UUID.randomUUID();
    friends.send("alice", new FriendController.Send(id, b));
    friends.respond("bob", id, FriendController.Action.ACCEPT);
  }

  JournalController.Edit edit(long version, boolean shared, List<String> photos) {
    return new JournalController.Edit(
      version,
      "운동 완료",
      LocalDate.of(2026, 11, 4),
      "3종목 · 9세트",
      shared ? JournalController.Visibility.FRIENDS : JournalController.Visibility.PRIVATE,
      photos
    );
  }

  String image() throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out);
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
  }

  @Test
  void photosAreNormalizedAndRetryDoesNotDuplicate() throws Exception {
    var body = edit(0, false, List.of(image(), image()));
    var saved = posts.save("alice", "workout-1", body);
    assertThat(saved.photos())
      .hasSize(2)
      .allMatch(p -> p.startsWith("data:image/jpeg;base64,"));
    assertThat(posts.save("alice", "workout-1", body).id()).isEqualTo(saved.id());
    assertThat(posts.mine("alice", "workout-1").version()).isEqualTo(1);
    assertThat(posts.list("alice", null, 0).total()).isEqualTo(1);
    assertThat(posts.list("alice", null, 0).items().getFirst().photos()).isEmpty();
    assertThatThrownBy(() ->
      posts.save("alice", "workout-1", edit(0, true, List.of()))
    ).isInstanceOf(com.rufit.workout.WorkoutConflict.class);
    assertThatThrownBy(() ->
      posts.save("alice", "invalid", edit(0, false, List.of("data:image/jpeg;base64,bm90IGltYWdl")))
    ).isInstanceOf(IllegalArgumentException.class);
    assertThat(posts.list("alice", null, 0).total()).isEqualTo(1);
  }

  @Test
  void visibilityBlocksAndHiddenPostProtectBothPhotosAndLikes() throws Exception {
    connect();
    var p = posts.save("alice", "private", edit(0, false, List.of(image())));
    assertThatThrownBy(() -> posts.get("bob", p.id())).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    var shared = posts.save("alice", "private", edit(1, true, List.of(image())));
    assertThat(posts.get("bob", p.id()).sourceId()).isNull();
    assertThat(posts.get("bob", p.id()).photos()).hasSize(1);
    assertThatThrownBy(() -> posts.get("carol", p.id())).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    assertThat(posts.list("carol", a, 0).items()).isEmpty();
    assertThatThrownBy(() -> posts.hide("bob", p.id())).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    friends.block("bob", a, true);
    assertThatThrownBy(() -> posts.like("bob", p.id(), true)).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    assertThatThrownBy(() -> posts.get("bob", p.id())).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    friends.block("bob", a, false);
    connect();
    posts.hide("alice", p.id());
    assertThatThrownBy(() -> posts.get("bob", p.id())).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    assertThat(posts.get("alice", p.id()).version()).isGreaterThan(shared.version());
  }

  @Test
  void likesAndNotificationsAreIdempotentOwnedAndCancelledTogether() {
    connect();
    var p = posts.save("alice", "liked", edit(0, true, List.of()));
    posts.like("bob", p.id(), true);
    posts.like("bob", p.id(), true);
    assertThat(posts.get("alice", p.id()).likes()).isEqualTo(1);
    var likes = notifications
      .inbox("alice", null)
      .items()
      .stream()
      .filter(n -> n.type().equals("POST_LIKED"))
      .toList();
    assertThat(likes).hasSize(1);
    assertThat(likes.getFirst().postId()).isEqualTo(p.id());
    assertThat(notifications.inbox("carol", null).items()).isEmpty();
    assertThatThrownBy(() ->
      notifications.read("carol", Long.parseLong(likes.getFirst().id()))
    ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    // 작성자 본인의 좋아요는 집계에 포함되지만 본인 알림은 생기지 않아야 합니다.
    posts.like("alice", p.id(), true);
    assertThat(posts.get("alice", p.id()).likes()).isEqualTo(2);
    assertThat(posts.get("alice", p.id()).liked()).isTrue();
    assertThat(
      notifications.inbox("alice", null).items().stream().filter(n -> n.type().equals("POST_LIKED"))
    ).hasSize(1);
    posts.like("alice", p.id(), false);
    assertThat(posts.get("alice", p.id()).likes()).isEqualTo(1);
    posts.like("bob", p.id(), false);
    posts.like("bob", p.id(), false);
    assertThat(posts.get("alice", p.id()).likes()).isZero();
    assertThat(notifications.inbox("alice", null).items()).noneMatch(n ->
      n.type().equals("POST_LIKED")
    );
    posts.like("bob", p.id(), true);
    posts.hide("alice", p.id());
    assertThat(notifications.inbox("alice", null).items()).noneMatch(n ->
      n.type().equals("POST_LIKED")
    );
  }

  @Test
  void deletionChecksOwnerAndVersionAndRemovesOnlyThePost() throws Exception {
    connect();
    var p = posts.save("alice", "delete-me", edit(0, true, List.of(image())));
    var keep = posts.save("alice", "keep-me", edit(0, true, List.of()));
    posts.like("bob", p.id(), true);
    assertThatThrownBy(() -> posts.delete("bob", p.id(), p.version())).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    assertThatThrownBy(() -> posts.delete("alice", p.id(), 0)).isInstanceOf(
      com.rufit.workout.WorkoutConflict.class
    );
    mvc
      .perform(
        delete("/api/v1/social/posts/" + p.id() + "?version=" + p.version())
          .with(user("alice"))
          .with(csrf())
      )
      .andExpect(status().isNoContent());
    posts.delete("alice", p.id(), p.version());
    assertThat(posts.list("alice", null, 0).items())
      .extracting(JournalService.Post::id)
      .containsExactly(keep.id());
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM journal_photos WHERE post_id=?", Long.class, p.id())
    ).isZero();
    assertThat(
      jdbc.queryForObject("SELECT count(*) FROM journal_likes WHERE post_id=?", Long.class, p.id())
    ).isZero();
    assertThat(notifications.inbox("alice", null).items()).noneMatch(n ->
      p.id().equals(n.postId())
    );
    assertThatThrownBy(() ->
      posts.save("alice", "delete-me", edit(p.version(), true, List.of()))
    ).isInstanceOf(com.rufit.workout.WorkoutConflict.class);
    assertThat(friends.friends("alice").items()).hasSize(1);
  }

  @Test
  void invalidInputsAndUnauthenticatedRequestsAreRejected() throws Exception {
    mvc.perform(get("/api/v1/social/posts")).andExpect(status().isUnauthorized());
    mvc
      .perform(
        put("/api/v1/social/posts/mine/example")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"version\":0,\"memo\":\"\",\"date\":\"2026-11-04\",\"summary\":\"\",\"visibility\":\"EVERYONE\",\"photos\":[]}"
          )
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(get("/api/v1/social/posts?page=-1").with(user("alice")))
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        put("/api/v1/social/posts/mine/example")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content(new byte[14_100_001])
      )
      .andExpect(status().isPayloadTooLarge());
  }

  JournalController.Edit edit(JournalController.Visibility visibility) {
    return new JournalController.Edit(
      0L,
      "운동 완료",
      LocalDate.of(2026, 11, 4),
      "3종목 · 9세트",
      visibility,
      List.of()
    );
  }

  /** 전체 공개 글은 친구가 아니어도 보이고, 피드는 내 글을 빼고 최신순입니다. */
  @Test
  void publicPostsAppearInFeedForEveryoneNewestFirst() {
    connect(); // alice-bob 친구, carol은 남
    var friendsOnly = posts.save("alice", "w1", edit(JournalController.Visibility.FRIENDS));
    var hidden = posts.save("alice", "w2", edit(JournalController.Visibility.PRIVATE));
    var open = posts.save("alice", "w3", edit(JournalController.Visibility.PUBLIC));
    var carolOpen = posts.save("carol", "w4", edit(JournalController.Visibility.PUBLIC));
    jdbc.update("UPDATE journal_posts SET created_at=now()-interval '1 hour' WHERE id=?", open.id());

    // bob(친구): carol 공개 → alice 친구 공개 → alice 공개(1시간 전), 나만 보기는 없음
    assertThat(posts.feed("bob", null, false, 0).items())
      .extracting(JournalService.Post::id)
      .containsExactly(carolOpen.id(), friendsOnly.id(), open.id());
    // carol(남): alice의 전체 공개만, 자기 글은 빠짐
    assertThat(posts.feed("carol", null, false, 0).items())
      .extracting(JournalService.Post::id)
      .containsExactly(open.id());
    assertThat(posts.get("carol", open.id()).visibility()).isEqualTo("PUBLIC");
    assertThatThrownBy(() -> posts.get("carol", friendsOnly.id())).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    assertThatThrownBy(() -> posts.get("carol", hidden.id())).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    // 한 사람만 보기
    assertThat(posts.feed("bob", a, false, 0).items())
      .extracting(JournalService.Post::id)
      .containsExactly(friendsOnly.id(), open.id());
    // 팔로잉(친구 글만): bob에게는 친구 alice의 글만, 친구가 없는 carol에게는 아무것도 없음
    assertThat(posts.feed("bob", null, true, 0).items())
      .extracting(JournalService.Post::id)
      .containsExactly(friendsOnly.id(), open.id());
    assertThat(posts.feed("bob", null, true, 0).total()).isEqualTo(2);
    assertThat(posts.feed("carol", null, true, 0).items()).isEmpty();

    // 친구가 아닌 carol의 좋아요도 전체 공개 글이면 알림이 보입니다.
    posts.like("carol", open.id(), true);
    assertThat(notifications.inbox("alice", null).items()).anyMatch(n ->
      n.type().equals("POST_LIKED") && n.actorId().equals(c)
    );

    // 차단하면 전체 공개 글도 피드와 상세에서 빠집니다.
    friends.block("alice", c, true);
    assertThat(posts.feed("carol", null, false, 0).items()).isEmpty();
    assertThatThrownBy(() -> posts.get("carol", open.id())).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
  }
}

