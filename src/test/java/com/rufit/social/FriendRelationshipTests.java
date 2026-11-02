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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class FriendRelationshipTests {
  @Autowired FriendService service;
  @Autowired SocialDirectoryService directory;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  UUID a,b,c;
  @BeforeEach void setup() {
    jdbc.update("DELETE FROM social_directory");
    a=directory.update("alice",new SocialDirectoryController.Edit("테스트하나",true,0L)).memberId();
    b=directory.update("bob",new SocialDirectoryController.Edit("테스트둘",true,0L)).memberId();
    c=directory.update("carol",new SocialDirectoryController.Edit("테스트셋",true,0L)).memberId();
  }
  @Test void requestAcceptRetryAndUnrelatedAccount() throws Exception {
    UUID id=UUID.randomUUID();
    var send=new FriendController.Send(id,b);
    assertThat(service.send("alice",send).state()).isEqualTo("PENDING");
    assertThat(service.send("alice",send).id()).isEqualTo(id);
    assertThat(service.requests("bob").incoming()).hasSize(1);
    assertThat(service.requests("carol").incoming()).isEmpty();
    mvc.perform(post("/api/v1/social/requests/"+id+"/respond").with(user("carol")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"ACCEPT\"}"))
        .andExpect(status().isNotFound());
    service.respond("bob",id,FriendController.Action.ACCEPT);
    service.respond("bob",id,FriendController.Action.ACCEPT);
    assertThat(service.friends("alice").items()).hasSize(1);
    assertThat(service.friends("bob").items().getFirst().memberId()).isEqualTo(a);
    assertThat(service.requests("bob").incoming()).isEmpty();
    assertThatThrownBy(()->service.send("alice",new FriendController.Send(UUID.randomUUID(),b)))
        .isInstanceOf(com.rufit.workout.WorkoutConflict.class);
  }
  @Test void blockRemovesFriendAndPreventsStaleAcceptanceRestoringIt() {
    UUID id=UUID.randomUUID();
    service.send("alice",new FriendController.Send(id,b));
    service.respond("bob",id,FriendController.Action.ACCEPT);
    service.block("bob",a,true);
    assertThat(service.friends("alice").items()).isEmpty();
    assertThat(directory.search("alice","테스트",0,20).items()).extracting(SocialDirectoryController.Member::memberId).doesNotContain(b);
    assertThatThrownBy(()->service.send("alice",new FriendController.Send(UUID.randomUUID(),b)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    service.block("bob",a,false);
    service.respond("bob",id,FriendController.Action.ACCEPT);
    assertThat(service.friends("alice").items()).isEmpty();
    assertThat(service.blocks("bob").items()).isEmpty();
  }
  @Test void blockingCancelsPendingAndCannotBeUndoneByOtherParty() {
    UUID id=UUID.randomUUID();
    service.send("alice",new FriendController.Send(id,b));
    service.block("bob",a,true);
    service.block("alice",b,false);
    assertThat(service.blocks("bob").items()).hasSize(1);
    assertThat(service.requests("alice").outgoing()).isEmpty();
    assertThat(service.send("alice",new FriendController.Send(id,b)).state()).isEqualTo("CANCELLED");
    assertThatThrownBy(()->service.respond("bob",id,FriendController.Action.ACCEPT)).isInstanceOf(com.rufit.workout.WorkoutConflict.class);
  }
  @Test void rejectionCancelAndCrossRequestDoNotCreateFriendships() {
    UUID id=UUID.randomUUID();
    service.send("alice",new FriendController.Send(id,b));
    assertThatThrownBy(()->service.send("bob",new FriendController.Send(UUID.randomUUID(),a))).isInstanceOf(com.rufit.workout.WorkoutConflict.class);
    assertThatThrownBy(()->service.respond("alice",id,FriendController.Action.REJECT)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    service.respond("bob",id,FriendController.Action.REJECT);
    UUID next=UUID.randomUUID();
    service.send("alice",new FriendController.Send(next,b));
    service.respond("alice",next,FriendController.Action.CANCEL);
    assertThat(service.friends("alice").items()).isEmpty();
  }
  @Test void simultaneousOppositeRequestsLeaveOnePendingPair() throws Exception {
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var gate=new java.util.concurrent.CountDownLatch(1);
      var left=pool.submit(()->{gate.await(); try {service.send("alice",new FriendController.Send(UUID.randomUUID(),b)); return 1;} catch(com.rufit.workout.WorkoutConflict e){return 0;}});
      var right=pool.submit(()->{gate.await(); try {service.send("bob",new FriendController.Send(UUID.randomUUID(),a)); return 1;} catch(com.rufit.workout.WorkoutConflict e){return 0;}});
      gate.countDown();
      assertThat(left.get(10,java.util.concurrent.TimeUnit.SECONDS)+right.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(service.requests("alice").incoming().size()+service.requests("alice").outgoing().size()).isEqualTo(1);
    }
  }
}
