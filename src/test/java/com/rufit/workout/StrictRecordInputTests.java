package com.rufit.workout;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class StrictRecordInputTests {
    @Autowired MockMvc mvc;
    @Autowired WorkoutService service;
    @Autowired WorkoutRepository repository;
    @BeforeEach void clean() { repository.deleteAll(); }
    @Test void rejectsFractionalRepetitionsAndRevisionWithoutChangingWorkout() throws Exception {
        var workout=service.start("strict-input");
        for(String body:new String[]{
            "{\"version\":0,\"sets\":[{\"exerciseName\":\"Squat\",\"weight\":10,\"reps\":1.9}]}",
            "{\"version\":0.9,\"sets\":[{\"exerciseName\":\"Squat\",\"weight\":10,\"reps\":1}]}"})
            mvc.perform(put("/api/v1/workouts/"+workout.id()+"/exercises").with(user("strict-input")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
        mvc.perform(get("/api/v1/workouts/"+workout.id()).with(user("strict-input")))
            .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.sets.length()").value(0));
    }
    @Test void completionRequiresExplicitIntegerRestAndBooleanFlag() throws Exception {
        var workout=service.start("strict-input");
        workout=service.appendSet(workout.id(),"strict-input",new WorkoutController.SetEdit(workout.version(),new WorkoutController.SetInput("Squat",BigDecimal.TEN,5)));
        String prefix="{\"version\":"+workout.version();
        for(String suffix:new String[]{",\"completed\":true}",",\"completed\":true,\"restSeconds\":null}",
            ",\"completed\":true,\"restSeconds\":30.7}",",\"completed\":\"true\",\"restSeconds\":30}"})
            mvc.perform(put("/api/v1/workouts/"+workout.id()+"/sets/0/completion").with(user("strict-input")).with(csrf())
                .contentType("application/json").content(prefix+suffix)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workouts/"+workout.id()).with(user("strict-input")))
            .andExpect(jsonPath("$.version").value(workout.version())).andExpect(jsonPath("$.completedSetIndexes.length()").value(0));
    }
    @Test void enumValuesMustUseTheirDeclaredNames() throws Exception {
        mvc.perform(post("/api/v1/profile").with(user("strict-input")).with(csrf()).contentType("application/json")
            .content("{\"nickname\":\"Strict input\",\"bio\":\"\",\"experience\":0,\"goals\":[]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
    }
    @Test void numericStringsAndDuplicateFieldsAreRejected() throws Exception {
        var workout=service.start("strict-input");
        for(String body:new String[]{
            "{\"version\":0,\"sets\":[{\"exerciseName\":\"Squat\",\"weight\":\"10.5\",\"reps\":5}]}",
            "{\"version\":0,\"sets\":[{\"exerciseName\":42,\"weight\":10,\"reps\":5}]}",
            "{\"version\":0,\"version\":0,\"sets\":[{\"exerciseName\":\"Squat\",\"weight\":10,\"reps\":5}]}"})
            mvc.perform(put("/api/v1/workouts/"+workout.id()+"/exercises").with(user("strict-input")).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
    }
}
