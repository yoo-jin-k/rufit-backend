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
    private Number completeFirstSet(String id, Number version) throws Exception {
        String response = mvc.perform(put("/api/v1/workouts/"+id+"/sets/0/completion").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+",\"completed\":true,\"restSeconds\":0}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.version");
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
        version = completeFirstSet(id, version);
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
    @Test void setCompletionAndRestPersistAcrossRequests() throws Exception {
        String id = start();
        String updated = edit(id, "{\"version\":0,\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":40,\"reps\":10}]}")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Number version = JsonPath.read(updated, "$.version");
        String completed = mvc.perform(put("/api/v1/workouts/"+id+"/sets/0/completion").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+",\"completed\":true,\"restSeconds\":90}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completedSetIndexes[0]").value(0)).andExpect(jsonPath("$.restEndsAt").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        version = JsonPath.read(completed, "$.version");
        String deadline = JsonPath.read(completed, "$.restEndsAt");
        mvc.perform(get("/api/v1/workouts/"+id).with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.restEndsAt").value(deadline));
        mvc.perform(post("/api/v1/workouts/"+id+"/rest/skip").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.restEndsAt").isEmpty());
    }
    @Test void creationRetryAndActiveRecoveryAreOwnerScoped() throws Exception {
        String key = java.util.UUID.randomUUID().toString();
        for (int attempt=0; attempt<2; attempt++) {
            mvc.perform(post("/api/v1/workouts").header("Idempotency-Key", key).with(user("alice")).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(key));
        }
        org.assertj.core.api.Assertions.assertThat(repository.count()).isEqualTo(1);
        mvc.perform(get("/api/v1/workouts/active").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(key));
        mvc.perform(get("/api/v1/workouts/active").with(user("bob"))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/workouts").header("Idempotency-Key", key).with(user("bob")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/workouts").header("Idempotency-Key", "invalid").with(user("bob")).with(csrf())).andExpect(status().isBadRequest());
    }
    @Test void historyIncludesOnlyOwnCompletedWorkouts() throws Exception {
        String id = start();
        String updated = edit(id, "{\"version\":0,\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":40,\"reps\":10}]}")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Number version = JsonPath.read(updated, "$.version");
        version = completeFirstSet(id, version);
        mvc.perform(post("/api/v1/workouts/"+id+"/finish").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+"}")).andExpect(status().isOk());
        start();
        mvc.perform(get("/api/v1/workouts?size=1").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].id").value(id)).andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/v1/workouts").with(user("bob"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/workouts?page=1").with(user("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/workouts?size=101").with(user("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workouts?page=-1").with(user("alice"))).andExpect(status().isBadRequest());
    }
    @Test void preventsMultipleActiveWorkoutsAndEmptyFinish() throws Exception {
        String id = start();
        mvc.perform(post("/api/v1/workouts").with(user("alice")).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/workouts/"+id+"/finish").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
            .andExpect(status().isBadRequest());
    }
    @Test void finishingPartialWorkoutRequiresConfirmationAndPersistsOnlyCompletedSets() throws Exception {
        String id = start();
        String response = edit(id, """
            {"version":0,"sets":[{"exerciseName":"스쿼트","weight":40,"reps":10},{"exerciseName":"푸시업","weight":0,"reps":8}]}
            """).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Number version = completeFirstSet(id, JsonPath.read(response, "$.version"));
        mvc.perform(post("/api/v1/workouts/"+id+"/finish").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INCOMPLETE_SETS_CONFIRMATION_REQUIRED"));
        mvc.perform(get("/api/v1/workouts/"+id).with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sets.length()").value(2)).andExpect(jsonPath("$.finishedAt").isEmpty());
        mvc.perform(post("/api/v1/workouts/"+id+"/finish").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+",\"discardIncomplete\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sets.length()").value(1));
        mvc.perform(get("/api/v1/workouts/"+id).with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sets[0].exerciseName").value("스쿼트"))
            .andExpect(jsonPath("$.completedSetIndexes[0]").value(0)).andExpect(jsonPath("$.finishedAt").isNotEmpty());
    }
}
