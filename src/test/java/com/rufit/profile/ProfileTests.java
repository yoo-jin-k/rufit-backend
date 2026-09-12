package com.rufit.profile;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileTests {
    @Autowired MockMvc mvc;
    @Autowired ProfileRepository repository;
    @BeforeEach void clean() { repository.deleteAll(); }
    String fields="{\"nickname\":\"루핏유저\",\"bio\":\"운동 소개\\n둘째 줄\",\"experience\":\"BEGINNER\",\"goals\":[\"STRENGTH\",\"GENERAL_FITNESS\"]}";
    @Test void profileIsPrivateOwnerScopedAndCreationRetryPreservesLaterEdits() throws Exception {
        UUID key=UUID.randomUUID();
        mvc.perform(get("/api/v1/profile").with(user("alice"))).andExpect(status().isNotFound());
        String created=mvc.perform(post("/api/v1/profile").with(user("alice")).with(csrf()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(fields.substring(0,fields.length()-1)+",\"ownerName\":\"bob\",\"visibility\":\"PUBLIC\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.visibility").value("PRIVATE"))
            .andExpect(jsonPath("$.bio").value("운동 소개\n둘째 줄")).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/profile").with(user("bob"))).andExpect(status().isNotFound());
        String changed="{\"nickname\":\"새 이름\",\"bio\":\"\",\"experience\":null,\"goals\":[]}";
        String updated=mvc.perform(put("/api/v1/profile").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":"+JsonPath.read(created,"$.version")+",\"profile\":"+changed+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("새 이름"))
            .andExpect(jsonPath("$.experience").isEmpty()).andExpect(jsonPath("$.goals.length()").value(0))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/profile").with(user("alice")).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(fields))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.nickname").value("새 이름"))
            .andExpect(jsonPath("$.version").value((Integer)JsonPath.read(updated,"$.version")));
        mvc.perform(put("/api/v1/profile").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":"+JsonPath.read(created,"$.version")+",\"profile\":"+fields+"}"))
            .andExpect(status().isConflict());
        assertThat(repository.count()).isEqualTo(1);
    }
    @Test void profileCreationRequiresAuthenticationAndRejectsNewKeysAndInvalidFields() throws Exception {
        mvc.perform(get("/api/v1/profile")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/profile").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(fields))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/profile").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(fields))
            .andExpect(status().isConflict());
        for (String bad:new String[]{fields.replace("루핏유저","  "),fields.replace("BEGINNER","UNKNOWN"),fields.replace("STRENGTH","BAD_GOAL"),
            fields.replace("운동 소개\\n둘째 줄","x".repeat(501)),"{\"nickname\":\"Valid\",\"bio\":\"\",\"goals\":[null]}"})
            mvc.perform(post("/api/v1/profile").with(user("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/profile").with(user("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":0,\"profile\":"+fields+"}")).andExpect(status().isNotFound());
    }
    @Test void assignedOwnerKeyCannotMergeOverExistingProfile() {
        var first=new ProfileController.Fields("First","",null,Set.of());
        repository.saveAndFlush(new UserProfile("alice",UUID.randomUUID(),first));
        assertThatThrownBy(() -> repository.saveAndFlush(new UserProfile("alice",UUID.randomUUID(),new ProfileController.Fields("Other","",null,Set.of()))))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findById("alice").orElseThrow().nickname).isEqualTo("First");
    }
}
