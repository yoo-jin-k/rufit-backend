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
        assertThat((String)JsonPath.read(json,"$.paths['/api/v1/workouts'].get.responses['200'].headers['X-Request-Id'].schema.format")).isEqualTo("uuid");
        assertThat((String)JsonPath.read(json,"$.paths['/api/v1/workouts'].get.responses['401'].headers['X-Request-Id'].schema.format")).isEqualTo("uuid");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi.json"), json);
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> schemas, String ref) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(ref.substring("#/components/schemas/".length()));
        return (Map<String, Object>) schema.get("properties");
    }
    @Test void responseFieldsArePresentEvenWhenNullableWhileOptionalInputsRemainOptional() throws Exception {
        String json=mvc.perform(get("/v3/api-docs").with(user("contract-test"))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Map<String,Map<String,Object>> schemas=JsonPath.read(json,"$.components.schemas");
        for (String name:List.of("workout.WorkoutController.View","workout.WorkoutController.History","workout.WorkoutController.ExerciseRecord",
            "workout.WorkoutController.ExerciseHistory","workout.WorkoutController.DraftSave","routine.RoutineController.View",
            "profile.ProfileController.View","exercise.ExerciseController.View","exercise.ExerciseController.Results",
            "workout.WorkoutSummary.View","workout.WorkoutSummary.Exercise","workout.ExerciseStatistics.View","workout.ExerciseStatistics.BestSet",
            "workout.WorkoutCalendar.Day","workout.WorkoutCalendar.WeekView","workout.WorkoutCalendar.MonthView","config.CurrentAccountController.Account","config.DevelopmentCsrfController.Token")) {
            Map<String,Object> schema=schemas.get("com.rufit."+name);
            assertThat(schema).as(name).isNotNull();
            Map<?,?> props=(Map<?,?>)schema.get("properties");
            assertThat(new HashSet<>((List<?>)schema.get("required"))).as(name).isEqualTo(props.keySet());
        }
        Map<?,?> profileProps=(Map<?,?>)schemas.get("com.rufit.profile.ProfileController.View").get("properties");
        assertThat(((Map<?,?>)profileProps.get("experience")).get("nullable")).isEqualTo(true);
        assertThat(((Map<?,?>)profileProps.get("visibility")).get("enum")).isEqualTo(List.of("PRIVATE"));
        assertThat(((List<?>)schemas.get("com.rufit.workout.WorkoutController.SetInput").get("required")).contains("exerciseId")).isFalse();
        assertThat(((List<?>)schemas.get("com.rufit.workout.WorkoutController.Draft").get("required")).contains("note")).isFalse();
        Map<String, Object> completion = schemas.get("com.rufit.workout.WorkoutController.Completion");
        assertThat(new HashSet<>((List<?>) completion.get("required"))).isEqualTo(Set.of("version", "completed"));
        assertThat(((Map<?, ?>) ((Map<?, ?>) completion.get("properties")).get("restSeconds")).get("nullable")).isEqualTo(true);
    }
    @Test void enabledDocumentationStillRequiresAuthentication() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }
    @Test void duplicationUsesRequiredRevisionAndReturnsCompleteWorkoutContract() throws Exception {
        String json = mvc.perform(get("/v3/api-docs").with(user("contract-test"))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Map<String, Object> request = JsonPath.read(json, "$.components.schemas['com.rufit.workout.WorkoutController.Revision']");
        assertThat(request.get("required")).isEqualTo(List.of("version"));
        String operation = "$.paths['/api/v1/workouts/{id}/sets/{setIndex}/duplicate'].post";
        assertThat((String) JsonPath.read(json, operation + ".requestBody.content['application/json'].schema['$ref']"))
            .isEqualTo("#/components/schemas/com.rufit.workout.WorkoutController.Revision");
        List<String> responses = JsonPath.read(json, operation + ".responses['200'].content.*.schema['$ref']");
        assertThat(responses).containsExactly("#/components/schemas/com.rufit.workout.WorkoutController.View");
        Map<String, Object> statuses = JsonPath.read(json, operation + ".responses");
        assertThat(statuses).containsKeys("400", "401", "403", "404", "409", "503");
    }
}
