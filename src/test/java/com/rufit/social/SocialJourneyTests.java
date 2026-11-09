package com.rufit.social;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 전용 시험 DB에서 두 회원의 전체 연결 흐름을 HTTP 경계부터 검증합니다. 실제 OAuth 인증은 별도입니다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SocialJourneyTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  ObjectMapper mapper;

  JsonNode request(String owner, MockHttpServletRequestBuilder request, String body, int status)
    throws Exception {
    request.with(user(owner)).with(csrf());
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
    String result = mvc
      .perform(request)
      .andExpect(status().is(status))
      .andReturn()
      .getResponse()
      .getContentAsString();
    return result.isEmpty() ? mapper.nullNode() : mapper.readTree(result);
  }

  JsonNode notification(String owner, String type) throws Exception {
    var inbox = request(owner, get("/api/v1/social/notifications"), null, 200);
    for (var item : inbox.get("items")) if (type.equals(item.get("type").asString())) return item;
    throw new AssertionError("알림 누락: " + type);
  }

  @Test
  void friendAcceptanceCheerAndPostLikeLeadToOwnedResources() throws Exception {
    // 기존 자료를 지우지 않고 매번 고유한 시험 계정을 사용합니다.
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String a = "journey-a-" + suffix,
      b = "journey-b-" + suffix,
      stranger = "journey-c-" + suffix;
    String aid = request(
      a,
      put("/api/v1/social/directory/me"),
      "{\"nickname\":\"검수하나" + suffix + "\",\"discoverable\":true,\"version\":0}",
      200
    )
      .get("memberId")
      .asString();
    String bid = request(
      b,
      put("/api/v1/social/directory/me"),
      "{\"nickname\":\"검수둘" + suffix + "\",\"discoverable\":true,\"version\":0}",
      200
    )
      .get("memberId")
      .asString();
    String rid = UUID.randomUUID().toString();
    request(
      a,
      post("/api/v1/social/requests"),
      "{\"requestId\":\"" + rid + "\",\"targetId\":\"" + bid + "\"}",
      200
    );
    assertThat(notification(b, "FRIEND_REQUEST").get("requestId").asString()).isEqualTo(rid);
    request(b, post("/api/v1/social/requests/" + rid + "/respond"), "{\"action\":\"ACCEPT\"}", 200);
    assertThat(notification(a, "FRIEND_ACCEPTED").get("actorId").asString()).isEqualTo(bid);
    assertThat(request(a, get("/api/v1/social/friends"), null, 200).get("items").size()).isEqualTo(
      1
    );
    assertThat(request(b, get("/api/v1/social/friends"), null, 200).get("items").size()).isEqualTo(
      1
    );

    request(a, put("/api/v1/social/live/sharing"), "{\"enabled\":true}", 204);
    request(a, put("/api/v1/social/live/mine/journey-workout"), "{\"active\":true}", 204);
    assertThat(request(b, get("/api/v1/social/live"), null, 200).get("items").size()).isEqualTo(1);
    request(b, post("/api/v1/social/live/" + aid + "/journey-workout/cheer"), null, 204);
    var cheer = notification(a, "WORKOUT_CHEER");
    assertThat(cheer.get("actorId").asString()).isEqualTo(bid);
    request(a, get("/api/v1/social/members/" + bid), null, 200);
    request(
      b,
      post("/api/v1/social/notifications/" + cheer.get("id").asString() + "/read"),
      null,
      404
    );
    request(
      a,
      post("/api/v1/social/notifications/" + cheer.get("id").asString() + "/read"),
      null,
      204
    );
    assertThat(notification(a, "WORKOUT_CHEER").get("read").asBoolean()).isTrue();
    request(a, put("/api/v1/social/live/mine/journey-workout"), "{\"active\":false}", 204);
    assertThat(request(b, get("/api/v1/social/live"), null, 200).get("items").size()).isZero();

    var saved = request(
      a,
      put("/api/v1/social/posts/mine/journey-workout"),
      "{\"version\":0,\"memo\":\"연결 흐름 검수\",\"date\":\"2026-11-04\",\"summary\":\"3종목 · 9세트\",\"visibility\":\"FRIENDS\",\"photos\":[]}",
      200
    );
    String pid = saved.get("id").asString();
    request(b, get("/api/v1/social/posts/" + pid), null, 200);
    request(b, put("/api/v1/social/posts/" + pid + "/like"), "{\"liked\":true}", 200);
    var like = notification(a, "POST_LIKED");
    assertThat(like.get("postId").asString()).isEqualTo(pid);
    assertThat(
      request(a, get("/api/v1/social/posts/" + like.get("postId").asString()), null, 200)
        .get("likes")
        .asInt()
    ).isEqualTo(1);
    request(stranger, get("/api/v1/social/posts/" + pid), null, 404);
    assertThat(
      request(stranger, get("/api/v1/social/notifications"), null, 200).get("items").size()
    ).isZero();
  }
}
