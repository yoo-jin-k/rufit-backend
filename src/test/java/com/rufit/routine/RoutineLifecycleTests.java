package com.rufit.routine;

import com.jayway.jsonpath.JsonPath;
import com.rufit.workout.Workout;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoutineLifecycleTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoutineRepository repository;
    @BeforeEach void clean() { jdbc.update("delete from workouts"); repository.deleteAll(); jdbc.update("delete from retired_routine_keys"); }
    private final String body = "{\"title\":\"하체\",\"sets\":[{\"exerciseName\":\"스쿼트\",\"weight\":40,\"reps\":10}]}";
    @Test void creationRetryAndDeletionPreserveWorkoutSnapshotAndRetireKey() throws Exception {
        String key = UUID.randomUUID().toString();
        String routine = mvc.perform(post("/api/v1/routines").header("Idempotency-Key", key).with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/routines").header("Idempotency-Key", key).with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(key));
        assertThat(repository.count()).isEqualTo(1);
        mvc.perform(post("/api/v1/routines").header("Idempotency-Key", key).with(user("bob")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        String startKey = UUID.randomUUID().toString();
        String workout = mvc.perform(post("/api/v1/routines/"+key+"/start").header("Idempotency-Key",startKey).with(user("alice")).with(csrf()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String deletion = "{\"version\":"+JsonPath.read(routine,"$.version")+",\"confirmed\":true}";
        mvc.perform(post("/api/v1/routines/"+key+"/delete").with(user("bob")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(deletion)).andExpect(status().isNotFound());
        for (int i=0; i<2; i++) mvc.perform(post("/api/v1/routines/"+key+"/delete").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(deletion)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/routines/"+key+"/start").header("Idempotency-Key",startKey).with(user("alice")).with(csrf()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(startKey));
        mvc.perform(post("/api/v1/routines/"+key+"/start").header("Idempotency-Key",UUID.randomUUID()).with(user("alice")).with(csrf()))
            .andExpect(status().isNotFound());
        assertThat(repository.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from routine_entries", Long.class)).isZero();
        mvc.perform(get("/api/v1/workouts/"+JsonPath.read(workout,"$.id")).with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sets[0].weight").value(40)).andExpect(jsonPath("$.sourceRoutineId").value(key));
        mvc.perform(post("/api/v1/routines").header("Idempotency-Key", key).with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isGone());
    }
    @Test void assignedRoutineIdCannotMergeOverAnotherOwner() {
        UUID id = UUID.randomUUID();
        var sets = List.of(new Workout.Entry("스쿼트", BigDecimal.TEN, 10));
        repository.saveAndFlush(new Routine(id, "alice", "하체", sets));
        assertThatThrownBy(() -> repository.saveAndFlush(new Routine(id, "bob", "상체", sets)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findById(id).orElseThrow().ownerName).isEqualTo("alice");
    }
}
