package com.rufit.routine;

import com.rufit.workout.Workout;
import com.jayway.jsonpath.JsonPath;
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
class RoutineCopyTests {
    @Autowired MockMvc mvc;
    @Autowired RoutineRepository repository;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clean() { jdbc.update("delete from workouts"); repository.deleteAll(); jdbc.update("delete from retired_routine_keys"); }
    private Routine source() {
        return repository.saveAndFlush(new Routine(UUID.randomUUID(),"alice","Original",List.of(
            new Workout.Entry(UUID.fromString("00000000-0000-4000-8000-000000000001"),"Squat snapshot",BigDecimal.TEN,10))));
    }
    private String body(long version,String title) { return "{\"version\":"+version+",\"title\":\""+title+"\"}"; }
    @Test void copyIsIndependentAndRetrySurvivesEditingAndDeletingSource() throws Exception {
        Routine source=source(); UUID key=UUID.randomUUID();
        String copied=mvc.perform(post("/api/v1/routines/"+source.id+"/copy").with(user("alice")).with(csrf()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body(source.version,"Copy")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(key.toString()))
            .andExpect(jsonPath("$.sourceRoutineId").value(source.id.toString()))
            .andExpect(jsonPath("$.sets[0].exerciseId").value("00000000-0000-4000-8000-000000000001"))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/v1/routines/"+source.id).with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":"+source.version+",\"title\":\"Changed\",\"sets\":[{\"exerciseName\":\"Press\",\"weight\":50,\"reps\":5}]}"))
            .andExpect(status().isOk());
        Number current=JsonPath.read(mvc.perform(get("/api/v1/routines/"+source.id).with(user("alice")))
            .andReturn().getResponse().getContentAsString(),"$.version");
        mvc.perform(post("/api/v1/routines/"+source.id+"/delete").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":"+current+",\"confirmed\":true}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/routines/"+source.id+"/copy").with(user("alice")).with(csrf()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body(source.version,"Copy")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.sets[0].weight").value(10))
            .andExpect(jsonPath("$.sets[0].exerciseName").value("Squat snapshot"));
        assertThat(repository.count()).isEqualTo(1);
        mvc.perform(post("/api/v1/routines/"+key+"/delete").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":"+JsonPath.read(copied,"$.version")+",\"confirmed\":true}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/routines/"+source.id+"/copy").with(user("alice")).with(csrf()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body(source.version,"Copy"))).andExpect(status().isGone());
    }
    @Test void staleSourceOtherOwnerAndCrossOperationKeysCannotCopy() throws Exception {
        Routine source=source();
        mvc.perform(post("/api/v1/routines/"+source.id+"/copy").with(user("bob")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(source.version,"Copy"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/routines/"+source.id+"/copy").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body(source.version+1,"Copy"))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/routines/"+source.id+"/copy").with(user("alice")).with(csrf()).header("Idempotency-Key",source.id)
            .contentType(MediaType.APPLICATION_JSON).content(body(source.version,"Copy"))).andExpect(status().isConflict());
        UUID key=UUID.randomUUID();
        mvc.perform(post("/api/v1/routines/"+source.id+"/copy").with(user("alice")).with(csrf()).header("Idempotency-Key",key)
            .contentType(MediaType.APPLICATION_JSON).content(body(source.version,"Copy"))).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/routines").with(user("alice")).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"New\",\"sets\":[{\"exerciseName\":\"Press\",\"weight\":50,\"reps\":5}]}"))
            .andExpect(status().isConflict());
        assertThat(repository.count()).isEqualTo(2);
    }
}
