package com.rufit.config;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTests {
    @Autowired MockMvc mvc;
    @Test void generatedContractKeepsWorkoutAndRoutineSchemasDistinctAndExportsArtifact() throws Exception {
        String json = mvc.perform(get("/v3/api-docs").with(user("contract-test")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> workoutRefs = JsonPath.read(json, "$.paths['/api/v1/workouts/{id}'].get.responses['200'].content.*.schema['$ref']");
        List<String> routineRefs = JsonPath.read(json, "$.paths['/api/v1/routines/{id}'].get.responses['200'].content.*.schema['$ref']");
        assertThat(workoutRefs).hasSize(1); assertThat(routineRefs).hasSize(1);
        assertThat(workoutRefs.getFirst()).isNotEqualTo(routineRefs.getFirst());
        Map<String, Object> schemas = JsonPath.read(json, "$.components.schemas");
        assertThat(properties(schemas, workoutRefs.getFirst())).containsKeys("finishedAt", "cancelledAt", "sourceRoutineId", "completedSetIndexes");
        Map<String, Object> workoutProperties = properties(schemas, workoutRefs.getFirst());
        assertThat(((Map<?, ?>) workoutProperties.get("finishedAt")).get("nullable")).isEqualTo(true);
        assertThat(((Map<?, ?>) workoutProperties.get("restEndsAt")).get("nullable")).isEqualTo(true);
        assertThat(properties(schemas, routineRefs.getFirst())).containsKeys("title", "sets").doesNotContainKey("finishedAt");
        Map<String, Object> paths = JsonPath.read(json, "$.paths");
        assertThat(paths).containsKeys("/api/v1/workouts/{id}/history", "/api/v1/workouts/{id}/delete", "/api/v1/workouts/calendar");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi.json"), json);
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> schemas, String ref) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(ref.substring("#/components/schemas/".length()));
        return (Map<String, Object>) schema.get("properties");
    }
    @Test void enabledDocumentationStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }
}
