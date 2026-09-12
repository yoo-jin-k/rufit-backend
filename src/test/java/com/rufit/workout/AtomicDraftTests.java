package com.rufit.workout;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtomicDraftTests {
    @Autowired MockMvc mvc;
    @Autowired WorkoutRepository repository;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clean() { repository.deleteAll(); }
    private Workout start() { return repository.saveAndFlush(new Workout(UUID.randomUUID(),"alice",Instant.now().truncatedTo(ChronoUnit.MICROS))); }
    private String body(UUID operation,long version,String deadline,boolean completed) {
        return "{\"operationId\":\""+operation+"\",\"version\":"+version+",\"sets\":[{\"set\":{\"exerciseName\":\"스쿼트\",\"weight\":40,\"reps\":10},\"completed\":"+completed+"}],\"restSetIndex\":0,\"restEndsAt\":\""+deadline+"\"}";
    }
    @Test void savesTogetherAndReplayDoesNotResetRestOrOverwriteLaterEdits() throws Exception {
        Workout w=start(); UUID operation=UUID.randomUUID();
        String deadline=Instant.now().plusSeconds(90).truncatedTo(ChronoUnit.MICROS).toString();
        String request=body(operation,w.version(),deadline,true);
        String saved=mvc.perform(put("/api/v1/workouts/"+w.id()+"/draft").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk()).andExpect(jsonPath("$.workout.completedSetIndexes[0]").value(0)).andExpect(jsonPath("$.workout.restEndsAt").value(deadline))
            .andExpect(jsonPath("$.workout.restSetIndex").value(0)).andReturn().getResponse().getContentAsString();
        Number version=JsonPath.read(saved,"$.workout.version");
        mvc.perform(put("/api/v1/workouts/"+w.id()+"/sets/0").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":"+version+",\"set\":{\"exerciseName\":\"스쿼트\",\"weight\":55,\"reps\":12}}"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/v1/workouts/"+w.id()+"/draft").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true)).andExpect(jsonPath("$.appliedVersion").value(version.intValue())).andExpect(jsonPath("$.workout.sets[0].weight").value(55)).andExpect(jsonPath("$.workout.restEndsAt").value(deadline));
        assertThat(jdbc.queryForObject("select count(*) from workout_draft_operations",Long.class)).isEqualTo(1);
        mvc.perform(put("/api/v1/workouts/"+w.id()+"/draft").with(user("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isNotFound());
    }
    @Test void invalidTimerAndStaleVersionNeverPartiallyWrite() throws Exception {
        Workout w=start();
        String invalid=body(UUID.randomUUID(),w.version(),Instant.now().plusSeconds(60).toString(),false);
        mvc.perform(put("/api/v1/workouts/"+w.id()+"/draft").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workouts/"+w.id()).with(user("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.sets.length()").value(0));
        assertThat(jdbc.queryForObject("select count(*) from workout_draft_operations",Long.class)).isZero();
        mvc.perform(put("/api/v1/workouts/"+w.id()+"/draft").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(UUID.randomUUID(),w.version()+1,Instant.now().plusSeconds(60).toString(),true))).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/workouts/"+w.id()+"/draft").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(UUID.randomUUID(),w.version(),Instant.now().plusSeconds(7200).toString(),true))).andExpect(status().isBadRequest());
    }
    @Test void expiredRestIsNotRestartedAndEmptyDraftCanBeSaved() throws Exception {
        Workout w=start();
        String saved=mvc.perform(put("/api/v1/workouts/"+w.id()+"/draft").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body(UUID.randomUUID(),w.version(),Instant.now().minusSeconds(60).toString(),true)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.workout.restEndsAt").isEmpty()).andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/v1/workouts/"+w.id()+"/draft").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"operationId\":\""+UUID.randomUUID()+"\",\"version\":"+JsonPath.read(saved,"$.workout.version")+",\"sets\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.workout.sets.length()").value(0)).andExpect(jsonPath("$.workout.completedSetIndexes.length()").value(0));
    }
    @Test void evenAnEmptySaveAdvancesRevisionSoAStaleSnapshotCannotBeAccepted() throws Exception {
        Workout w=start();
        String saved=mvc.perform(put("/api/v1/workouts/"+w.id()+"/draft").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"operationId\":\""+UUID.randomUUID()+"\",\"version\":"+w.version()+",\"sets\":[]}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Number version=JsonPath.read(saved,"$.workout.version");
        assertThat(version.longValue()).isGreaterThan(w.version());
        mvc.perform(put("/api/v1/workouts/"+w.id()+"/draft").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"operationId\":\""+UUID.randomUUID()+"\",\"version\":"+w.version()+",\"sets\":[]}"))
            .andExpect(status().isConflict());
    }
}
