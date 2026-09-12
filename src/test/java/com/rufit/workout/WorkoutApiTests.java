package com.rufit.workout;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.security.user.name=local-test", "spring.security.user.password=test-only-password"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutApiTests {
    @Autowired MockMvc mvc;
    @Autowired WorkoutRepository repository;
    @BeforeEach void clearTestRecords() { repository.deleteAll(); }
    private String start() throws Exception {
        String response = mvc.perform(post("/api/v1/workouts").with(user("alice")).with(csrf()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }
    private ResultActions edit(String id, String json) throws Exception {
        return mvc.perform(put("/api/v1/workouts/" + id + "/exercises").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }
    @Test void actualBasicAuthenticationAndCsrfToken() throws Exception {
        var result = mvc.perform(get("/api/v1/csrf").with(httpBasic("local-test", "test-only-password")))
            .andExpect(status().isOk()).andReturn();
        String header = JsonPath.read(result.getResponse().getContentAsString(), "$.headerName");
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/v1/workouts").with(httpBasic("local-test", "test-only-password"))
            .session((org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false))
            .header(header, token)).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/csrf").with(httpBasic("local-test", "wrong")))
            .andExpect(status().isUnauthorized());
    }
    @Test void fullRecordingRoundTrip() throws Exception {
        String id = start();
        String response = edit(id, """
            {"version":0,"sets":[{"exerciseName":"스쿼트","weight":60.50,"reps":10}]}
            """).andExpect(status().isOk()).andExpect(jsonPath("$.sets[0].weight").value(60.5))
            .andReturn().getResponse().getContentAsString();
        Number version = JsonPath.read(response, "$.version");
        mvc.perform(get("/api/v1/workouts/"+id).with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sets[0].reps").value(10));
        mvc.perform(post("/api/v1/workouts/"+id+"/finish").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.finishedAt").isNotEmpty());
        edit(id, "{\"version\":"+(version.longValue()+1)+",\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":60,\"reps\":10}]}")
            .andExpect(status().isConflict());
    }
    @Test void ownerIsolationAndAuthentication() throws Exception {
        String id = start();
        mvc.perform(get("/api/v1/workouts/"+id).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/workouts/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/workouts/"+id+"/finish").with(user("bob")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/workouts").with(user("bob"))).andExpect(status().isForbidden());
    }
    @Test void rejectsInvalidInputsAndStaleRevision() throws Exception {
        String id = start();
        edit(id, "{\"version\":0,\"sets\":[]}").andExpect(status().isBadRequest());
        edit(id, "{\"version\":0,\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":-1,\"reps\":0}]}").andExpect(status().isBadRequest());
        edit(id, "{\"version\":1,\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":0,\"reps\":1}]}").andExpect(status().isConflict());
    }
    @Test void rejectsMissingVersionNullSetAndOversizedNumbers() throws Exception {
        String id = start();
        edit(id, "{\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":0,\"reps\":1}]}").andExpect(status().isBadRequest());
        edit(id, "{\"version\":0,\"sets\":[null]}").andExpect(status().isBadRequest());
        edit(id, "{\"version\":0,\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":1501,\"reps\":1001}]}").andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/workouts/" + id + "/exercises").with(user("bob")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":0,\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":0,\"reps\":1}]}"))
            .andExpect(status().isNotFound());
    }
    @Test void preventsMultipleActiveWorkoutsAndEmptyFinish() throws Exception {
        String id = start();
        mvc.perform(post("/api/v1/workouts").with(user("alice")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/workouts/"+id+"/finish").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
            .andExpect(status().isBadRequest());
    }
}
