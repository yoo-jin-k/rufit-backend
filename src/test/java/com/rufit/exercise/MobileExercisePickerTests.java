package com.rufit.exercise;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MobileExercisePickerTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clean() { jdbc.update("delete from workouts"); jdbc.update("delete from routines"); jdbc.update("delete from exercises where owner_name is not null"); }

    @Test void everyBundledPictureIdentityResolvesToItsCanonicalNameAndFilters() throws Exception {
        try (var stream = getClass().getResourceAsStream("/catalog/mobile-picker.json")) {
            List<Map<String, String>> catalog = JsonPath.read(new String(stream.readAllBytes(), StandardCharsets.UTF_8), "$");
            for (var item : catalog) {
                mvc.perform(get("/api/v1/exercises/" + item.get("id")).with(user("picker")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(item.get("name")))
                    .andExpect(jsonPath("$.bodyPart").value(item.get("bodyPart")))
                    .andExpect(jsonPath("$.equipment").value(item.get("equipment")))
                    .andExpect(jsonPath("$.custom").value(false));
            }
        }
        mvc.perform(get("/api/v1/exercises").param("query", "스쿼트").param("bodyPart", "LEGS").param("equipment", "DUMBBELL").with(user("picker")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.items[0].id").value("00000000-0000-4000-8000-000000000012"));
        mvc.perform(get("/api/v1/exercises").param("query", "스쿼트").param("bodyPart", "CHEST").with(user("picker")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void newSquatSelectionStoresIndependentOneKilogramTwoRepSetsAndRemovesOnlyOne() throws Exception {
        String workout = mvc.perform(post("/api/v1/workouts").with(user("picker")).with(csrf()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(workout, "$.id");
        String exercise = "00000000-0000-4000-8000-000000000012";
        for (int i = 1; i <= 2; i++) {
            workout = mvc.perform(post("/api/v1/workouts/" + id + "/sets").with(user("picker")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + JsonPath.read(workout, "$.version") + ",\"set\":{\"exerciseId\":\"" + exercise + "\",\"exerciseName\":\"별칭\",\"weight\":1,\"reps\":2}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sets.length()").value(i))
                .andExpect(jsonPath("$.sets[0].exerciseName").value("덤벨 스쿼트"))
                .andExpect(jsonPath("$.completedSetIndexes.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        }
        workout = mvc.perform(put("/api/v1/workouts/" + id + "/sets/1").with(user("picker")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + JsonPath.read(workout, "$.version") + ",\"set\":{\"exerciseId\":\"" + exercise + "\",\"exerciseName\":\"덤벨 스쿼트\",\"weight\":3,\"reps\":4}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sets[0].weight").value(1))
            .andExpect(jsonPath("$.sets[0].reps").value(2)).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/workouts/" + id + "/sets/0/remove").with(user("picker")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + JsonPath.read(workout, "$.version") + "}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sets.length()").value(1));
        mvc.perform(get("/api/v1/workouts/" + id).with(user("picker")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sets[0].exerciseId").value(exercise))
            .andExpect(jsonPath("$.sets[0].weight").value(3)).andExpect(jsonPath("$.sets[0].reps").value(4));
        mvc.perform(get("/api/v1/workouts/" + id).with(user("other"))).andExpect(status().isNotFound());
    }
}
