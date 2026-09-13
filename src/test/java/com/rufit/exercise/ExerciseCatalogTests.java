package com.rufit.exercise;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExerciseCatalogTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clean() { jdbc.update("delete from workouts"); jdbc.update("delete from routines"); jdbc.update("delete from exercises where owner_name is not null"); }
    private String create(String owner,String key,String name) throws Exception {
        return mvc.perform(post("/api/v1/exercises").header("Idempotency-Key",key).with(user(owner)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\""+name+"\",\"bodyPart\":\"LEGS\",\"equipment\":\"OTHER\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
    @Test void standardCatalogFiltersAndOwnerOnlyCustomSearch() throws Exception {
        String key=UUID.randomUUID().toString();
        create("alice",key,"나만의 스쿼트");
        mvc.perform(get("/api/v1/exercises").param("query","스쿼트").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
        mvc.perform(get("/api/v1/exercises").param("query","스쿼트").with(user("bob")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(get("/api/v1/exercises").param("bodyPart","CHEST").param("equipment","BODYWEIGHT").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("푸시업"));
        mvc.perform(get("/api/v1/exercises/"+key).with(user("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/exercises")).andExpect(status().isUnauthorized());
    }
    @Test void replayUpdateAndArchiveNeverChangeSharedCatalogOrOtherUsers() throws Exception {
        String key=UUID.randomUUID().toString();
        String created=create("alice",key,"개인 하체"); create("alice",key,"재전송 이름");
        assertThat(jdbc.queryForObject("select count(*) from exercises where owner_name='alice'",Long.class)).isEqualTo(1);
        Number version=JsonPath.read(created,"$.version");
        String updated=mvc.perform(put("/api/v1/exercises/"+key).with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":"+version+",\"name\":\"변경 종목\",\"bodyPart\":\"LEGS\",\"equipment\":\"OTHER\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/exercises/"+key+"/archive").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":"+version+"}")).andExpect(status().isConflict());
        version=JsonPath.read(updated,"$.version");
        mvc.perform(post("/api/v1/exercises/"+key+"/archive").with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":"+version+"}")).andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt").isNotEmpty());
        mvc.perform(get("/api/v1/exercises").param("query","변경 종목").with(user("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/exercises/"+key).with(user("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt").isNotEmpty());
        assertThat((Object) JsonPath.read(create("alice",key,"재전송 이름"),"$.archivedAt")).isNotNull();
        mvc.perform(post("/api/v1/exercises/00000000-0000-4000-8000-000000000001/archive").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isNotFound());
    }
    @Test void wildcardsAreLiteralAndInvalidFiltersRejected() throws Exception {
        create("alice",UUID.randomUUID().toString(),"100%_test!");
        mvc.perform(get("/api/v1/exercises").param("query","%_").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/exercises").param("query","TEST!").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/exercises").param("equipment","INVALID").with(user("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/exercises").param("size","101").with(user("alice"))).andExpect(status().isBadRequest());
    }
    @Test void archivedCustomExerciseCanBeListedAndRestoredByItsOwner() throws Exception {
        String key=UUID.randomUUID().toString();
        String created=create("alice",key,"복구할 종목");
        String archived=mvc.perform(post("/api/v1/exercises/"+key+"/archive").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+JsonPath.read(created,"$.version")+"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/exercises").param("query","복구할 종목").param("includeArchived","true").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/exercises").param("query","복구할 종목").param("includeArchived","true").with(user("bob")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/v1/exercises/"+key+"/restore").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+JsonPath.read(archived,"$.version")+"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt").isEmpty());
        mvc.perform(get("/api/v1/exercises").param("query","복구할 종목").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }
}
