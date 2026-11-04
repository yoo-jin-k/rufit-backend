package com.rufit.social;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
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
class LiveWorkoutTests {

  @Autowired
  LiveWorkoutService live;

  @Autowired
  FriendService friends;

  @Autowired
  NotificationService notifications;

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
      .update("alice", new SocialDirectoryController.Edit("응원하나", true, 0L))
      .memberId();
    b = directory.update("bob", new SocialDirectoryController.Edit("응원둘", true, 0L)).memberId();
    c = directory
      .update("carol", new SocialDirectoryController.Edit("응원셋", true, 0L))
      .memberId();
    UUID id = UUID.randomUUID();
    friends.send("alice", new FriendController.Send(id, b));
    friends.respond("bob", id, FriendController.Action.ACCEPT);
  }

  void start() {
    live.sharing("alice", true);
    live.heartbeat("alice", "workout-1", true);
  }

  @Test
  void optInFriendsExpiryAndStoppingAreEnforced() {
    assertThat(live.snapshot("alice").sharing()).isFalse();
    assertThatThrownBy(() -> live.heartbeat("alice", "workout-1", true)).isInstanceOf(
      com.rufit.workout.WorkoutConflict.class
    );
    start();
    assertThat(live.snapshot("bob").items()).hasSize(1);
    assertThat(live.snapshot("carol").items()).isEmpty();
    jdbc.update("UPDATE social_live_workouts SET updated_at=now()-interval '121 seconds'");
    assertThat(live.snapshot("bob").items()).isEmpty();
    assertThatThrownBy(() -> live.cheer("bob", a, "workout-1")).isInstanceOf(
      com.rufit.workout.WorkoutConflict.class
    );
    live.heartbeat("alice", "workout-2", true);
    live.heartbeat("alice", "workout-1", false);
    assertThat(live.snapshot("bob").items().getFirst().workoutId()).isEqualTo("workout-2");
    live.sharing("alice", false);
    assertThat(live.snapshot("bob").items()).isEmpty();
    assertThat(live.snapshot("alice").sharing()).isFalse();
  }

  @Test
  void cheerRetryCreatesOneOwnedNotificationEvenAfterWorkoutEnds() {
    start();
    live.cheer("bob", a, "workout-1");
    live.cheer("bob", a, "workout-1");
    assertThat(live.snapshot("alice").received()).hasSize(1);
    assertThat(live.snapshot("bob").items().getFirst().sent()).isTrue();
    var cheers = notifications
      .inbox("alice", null)
      .items()
      .stream()
      .filter(n -> n.type().equals("WORKOUT_CHEER"))
      .toList();
    assertThat(cheers).hasSize(1);
    assertThat(cheers.getFirst().workoutId()).isEqualTo("workout-1");
    assertThat(notifications.inbox("carol", null).items()).isEmpty();
    assertThatThrownBy(() ->
      notifications.read("carol", Long.parseLong(cheers.getFirst().id()))
    ).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    notifications.read("alice", Long.parseLong(cheers.getFirst().id()));
    assertThat(
      notifications
        .inbox("alice", null)
        .items()
        .stream()
        .filter(n -> n.type().equals("WORKOUT_CHEER"))
        .findFirst()
        .orElseThrow()
        .read()
    ).isTrue();
    live.heartbeat("alice", "workout-1", false);
    live.cheer("bob", a, "workout-1");
    assertThat(live.snapshot("alice").received()).isEmpty();
  }

  @Test
  void selfNonFriendAndBlockedMemberCannotSend() {
    start();
    assertThatThrownBy(() -> live.cheer("alice", a, "workout-1")).isInstanceOf(
      IllegalArgumentException.class
    );
    assertThatThrownBy(() -> live.cheer("carol", a, "workout-1")).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
    live.cheer("bob", a, "workout-1");
    friends.block("alice", b, true);
    assertThat(live.snapshot("bob").items()).isEmpty();
    assertThat(live.snapshot("alice").received()).isEmpty();
    assertThat(notifications.inbox("alice", null).items()).noneMatch(n ->
      n.type().equals("WORKOUT_CHEER")
    );
    assertThatThrownBy(() -> live.cheer("bob", a, "workout-1")).isInstanceOf(
      org.springframework.web.server.ResponseStatusException.class
    );
  }

  @Test
  void httpValidationAuthenticationAndMissingDirectory() throws Exception {
    mvc.perform(get("/api/v1/social/live")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/social/live").with(user("unknown"))).andExpect(status().isNotFound());
    mvc
      .perform(
        put("/api/v1/social/live/sharing")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{}")
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        put("/api/v1/social/live/mine/bad.id")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"active\":true}")
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        put("/api/v1/social/live/sharing")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"enabled\":true}")
      )
      .andExpect(status().isNoContent());
    mvc
      .perform(
        put("/api/v1/social/live/mine/valid-1")
          .with(user("alice"))
          .with(csrf())
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"active\":true}")
      )
      .andExpect(status().isNoContent());
    mvc
      .perform(post("/api/v1/social/live/" + a + "/valid-1/cheer").with(user("bob")).with(csrf()))
      .andExpect(status().isNoContent());
  }
}
