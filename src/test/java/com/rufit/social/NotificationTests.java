package com.rufit.social;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class NotificationTests {
  @Autowired NotificationService notifications;
  @Autowired FriendService friends;
  @Autowired SocialDirectoryService directory;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  UUID a,b;
  @BeforeEach void setup() {
    jdbc.update("DELETE FROM social_directory");
    a=directory.update("alice",new SocialDirectoryController.Edit("알림하나",true,0L)).memberId();
    b=directory.update("bob",new SocialDirectoryController.Edit("알림둘",true,0L)).memberId();
  }
  UUID send() { UUID id=UUID.randomUUID(); friends.send("alice",new FriendController.Send(id,b)); return id; }
  @Test void requestRetryReadAndAcceptAreOwnedAndIdempotent() {
    UUID request=send(); friends.send("alice",new FriendController.Send(request,b));
    var inbox=notifications.inbox("bob",null);
    assertThat(inbox.items()).hasSize(1); assertThat(inbox.unreadCount()).isEqualTo(1);
    assertThat(notifications.inbox("alice",null).items()).isEmpty();
    long id=Long.parseLong(inbox.items().getFirst().id());
    assertThatThrownBy(()->notifications.read("alice",id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    notifications.read("bob",id); notifications.read("bob",id);
    assertThat(notifications.inbox("bob",null).unreadCount()).isZero();
    friends.respond("bob",request,FriendController.Action.ACCEPT);
    friends.respond("bob",request,FriendController.Action.ACCEPT);
    assertThat(notifications.inbox("bob",null).items()).isEmpty();
    assertThat(notifications.inbox("alice",null).items()).singleElement().satisfies(n -> {
      assertThat(n.type()).isEqualTo("FRIEND_ACCEPTED"); assertThat(n.actorId()).isEqualTo(b);
    });
  }
  @Test void cancelledRejectedBlockedAndRemovedRelationsLeaveNoStaleAlerts() {
    UUID request=send(); friends.respond("alice",request,FriendController.Action.CANCEL);
    assertThat(notifications.inbox("bob",null).items()).isEmpty();
    request=send(); friends.respond("bob",request,FriendController.Action.REJECT);
    assertThat(notifications.inbox("bob",null).unreadCount()).isZero();
    request=send(); friends.block("bob",a,true); friends.block("bob",a,false);
    assertThat(notifications.inbox("bob",null).items()).isEmpty();
    request=send(); friends.respond("bob",request,FriendController.Action.ACCEPT); friends.remove("alice",b);
    request=send(); friends.respond("bob",request,FriendController.Action.ACCEPT);
    assertThat(notifications.inbox("alice",null).items()).hasSize(1);
  }
  @Test void cursorDoesNotDuplicateNewerArrivalsAndEmptyDirectoryIsSupported() {
    for(int i=0;i<31;i++) {
      UUID actor=directory.update("actor"+i,new SocialDirectoryController.Edit("회원"+i,true,0L)).memberId();
      friends.send("actor"+i,new FriendController.Send(UUID.randomUUID(),b));
    }
    var page=notifications.inbox("bob",null);
    assertThat(page.items()).hasSize(30); assertThat(page.hasNext()).isTrue();
    var next=notifications.inbox("bob",Long.parseLong(page.items().getLast().id()));
    assertThat(next.items()).hasSize(1); assertThat(next.hasNext()).isFalse();
    assertThat(page.items()).doesNotContainAnyElementsOf(next.items());
    assertThat(next.unreadCount()).isEqualTo(31);
    assertThat(notifications.inbox("unknown",null).items()).isEmpty();
    assertThatThrownBy(()->notifications.inbox("bob",0L)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void endpointsRequireOwnerAndReturnStringIds() throws Exception {
    send(); String id=notifications.inbox("bob",null).items().getFirst().id();
    mvc.perform(get("/api/v1/social/notifications")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/social/notifications").with(user("bob")))
      .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id))
      .andExpect(jsonPath("$.items[0].owner").doesNotExist());
    mvc.perform(post("/api/v1/social/notifications/"+id+"/read").with(user("alice")).with(csrf())).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/social/notifications/"+id+"/read").with(user("bob")).with(csrf())).andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/social/notifications?before=invalid").with(user("bob"))).andExpect(status().isBadRequest());
  }
}
