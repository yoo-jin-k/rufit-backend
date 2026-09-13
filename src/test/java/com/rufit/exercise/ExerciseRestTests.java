package com.rufit.exercise;

import com.rufit.profile.RestPreferencesController;
import com.rufit.profile.RestPreferencesService;
import com.rufit.workout.WorkoutController;
import com.rufit.workout.WorkoutService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ExerciseRestTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExerciseRestService rest;
    @Autowired ExerciseService exercises;
    @Autowired WorkoutService workouts;
    @Autowired RestPreferencesService defaults;
    static final UUID SQUAT=UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID CURL=UUID.fromString("00000000-0000-4000-8000-000000000009");
    @BeforeEach @AfterEach void clean() {jdbc.update("delete from exercise_rest_preferences");}
    private ExerciseRestController.Edit personal(long version,int seconds) {
        return new ExerciseRestController.Edit(version,ExerciseRestController.Mode.PERSONAL,seconds);
    }
    @Test void catalogHasDifferentRecommendationsAndReadingDoesNotCreatePreferences() throws Exception {
        mvc.perform(get("/api/v1/exercises/"+SQUAT).with(user("rest-a")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.recommendedRestSeconds").value(180));
        mvc.perform(get("/api/v1/exercises/"+CURL+"/rest").with(user("rest-a")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.recommendedRestSeconds").value(90)).andExpect(jsonPath("$.personalRestSeconds").isEmpty())
            .andExpect(jsonPath("$.effectiveRestSeconds").value(90)).andExpect(jsonPath("$.source").value("RECOMMENDED"));
        assertThat(jdbc.queryForObject("select count(*) from exercise_rest_preferences",Long.class)).isZero();
    }
    @Test void personalChoiceIncludingZeroWinsAndResetKeepsVersionAgainstStaleWrites() throws Exception {
        rest.save(SQUAT,"rest-a",personal(0,0));
        assertThat(rest.get(SQUAT,"rest-a").effectiveRestSeconds()).isZero();
        assertThat(rest.get(SQUAT,"rest-b").effectiveRestSeconds()).isEqualTo(180);
        assertThat(rest.get(CURL,"rest-a").effectiveRestSeconds()).isEqualTo(90);
        mvc.perform(put("/api/v1/exercises/"+SQUAT+"/rest").with(user("rest-a")).with(csrf()).contentType("application/json")
            .content("{\"version\":1,\"mode\":\"RECOMMENDED\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.effectiveRestSeconds").value(180));
        for(int stale:new int[]{0,1}) mvc.perform(put("/api/v1/exercises/"+SQUAT+"/rest").with(user("rest-a")).with(csrf()).contentType("application/json")
            .content("{\"version\":"+stale+",\"mode\":\"PERSONAL\",\"restSeconds\":50}"))
            .andExpect(status().isConflict());
        assertThat(rest.get(SQUAT,"rest-a").source()).isEqualTo(ExerciseRestController.Source.RECOMMENDED);
    }
    @Test void customExerciseUsesAccountDefaultAndIsPrivateEvenAfterArchiving() throws Exception {
        String owner="custom-rest-"+UUID.randomUUID();
        var custom=exercises.create(owner,UUID.randomUUID(),new ExerciseController.Create("Custom rest",Exercise.BodyPart.ARMS,Exercise.Equipment.OTHER));
        defaults.save(owner,new RestPreferencesController.Edit(0L,45));
        assertThat(rest.get(custom.id(),owner).recommendedRestSeconds()).isNull();
        assertThat(rest.get(custom.id(),owner).effectiveRestSeconds()).isEqualTo(45);
        rest.save(custom.id(),owner,personal(0,75));exercises.archive(custom.id(),owner,custom.version());
        assertThat(rest.get(custom.id(),owner).effectiveRestSeconds()).isEqualTo(75);
        mvc.perform(get("/api/v1/exercises/"+custom.id()+"/rest").with(user("rest-b"))).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/exercises/"+custom.id()+"/rest").with(user("rest-b")).with(csrf()).contentType("application/json")
            .content("{\"version\":0,\"mode\":\"PERSONAL\",\"restSeconds\":1}" )).andExpect(status().isNotFound());
        assertThat(rest.effectiveSeconds(null,owner)).isEqualTo(45);
    }
    @Test void completingSetUsesExerciseChoiceAndSettingChangesDoNotRestartRunningRest() {
        String owner="completion-rest-"+UUID.randomUUID();var w=workouts.start(owner);
        w=workouts.appendSet(w.id(),owner,new WorkoutController.SetEdit(w.version(),new WorkoutController.SetInput("Squat",BigDecimal.ONE,2,SQUAT)));
        w=workouts.complete(w.id(),owner,0,new WorkoutController.Completion(w.version(),true,null));
        assertThat(Duration.between(Instant.now(),w.restEndsAt()).toSeconds()).isBetween(178L,180L);
        var deadline=w.restEndsAt();long version=w.version();
        rest.save(SQUAT,owner,personal(0,45));
        assertThat(workouts.get(w.id(),owner).restEndsAt()).isEqualTo(deadline);
        w=workouts.complete(w.id(),owner,0,new WorkoutController.Completion(w.version(),true,null));
        assertThat(w.restEndsAt()).isEqualTo(deadline);assertThat(w.version()).isEqualTo(version);
        w=workouts.complete(w.id(),owner,0,new WorkoutController.Completion(w.version(),false,null));
        w=workouts.complete(w.id(),owner,0,new WorkoutController.Completion(w.version(),true,null));
        assertThat(Duration.between(Instant.now(),w.restEndsAt()).toSeconds()).isBetween(43L,45L);
        w=workouts.complete(w.id(),owner,0,new WorkoutController.Completion(w.version(),false,null));
        w=workouts.complete(w.id(),owner,0,new WorkoutController.Completion(w.version(),true,0));
        assertThat(w.restEndsAt()).isNull();assertThat(rest.get(SQUAT,owner).effectiveRestSeconds()).isEqualTo(45);
    }
    @Test void completionAcceptsOmittedRestButMalformedPreferencesCannotWrite() throws Exception {
        String owner="rest-validation-"+UUID.randomUUID();var w=workouts.start(owner);
        w=workouts.appendSet(w.id(),owner,new WorkoutController.SetEdit(w.version(),new WorkoutController.SetInput("Squat",BigDecimal.ONE,2,SQUAT)));
        mvc.perform(put("/api/v1/workouts/"+w.id()+"/sets/0/completion").with(user(owner)).with(csrf()).contentType("application/json")
            .content("{\"version\":"+w.version()+",\"completed\":true}" )).andExpect(status().isOk()).andExpect(jsonPath("$.restEndsAt").isNotEmpty());
        for(String body:new String[]{"{}","{\"version\":0,\"mode\":\"PERSONAL\"}",
            "{\"version\":0,\"mode\":\"RECOMMENDED\",\"restSeconds\":90}",
            "{\"version\":0,\"mode\":\"PERSONAL\",\"restSeconds\":-1}",
            "{\"version\":0,\"mode\":\"PERSONAL\",\"restSeconds\":3601}",
            "{\"version\":0,\"mode\":\"PERSONAL\",\"restSeconds\":1.5}",
            "{\"version\":0,\"mode\":\"PERSONAL\",\"restSeconds\":\"90\"}"})
            mvc.perform(put("/api/v1/exercises/"+SQUAT+"/rest").with(user(owner)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/exercises/"+SQUAT+"/rest")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/exercises/"+SQUAT+"/rest").with(user(owner)).contentType("application/json")
            .content("{\"version\":0,\"mode\":\"PERSONAL\",\"restSeconds\":90}" )).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from exercise_rest_preferences",Long.class)).isZero();
    }
    @Test void simultaneousFirstChoicesOnlyCommitOneVersion() throws Exception {
        CountDownLatch ready=new CountDownLatch(4);int successes=0;
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new ArrayList<Future<Integer>>();
            for(int i=0;i<4;i++) {final int seconds=60+i;results.add(pool.submit(()->{
                ready.countDown();if(!ready.await(10,TimeUnit.SECONDS))throw new IllegalStateException("start timeout");
                return mvc.perform(put("/api/v1/exercises/"+SQUAT+"/rest").with(user("rest-race")).with(csrf()).contentType("application/json")
                    .content("{\"version\":0,\"mode\":\"PERSONAL\",\"restSeconds\":"+seconds+"}" )).andReturn().getResponse().getStatus();
            }));}
            for(var result:results) {int status=result.get(20,TimeUnit.SECONDS);assertThat(status).isIn(200,409);if(status==200)successes++;}
        }
        assertThat(successes).isEqualTo(1);assertThat(rest.get(SQUAT,"rest-race").version()).isEqualTo(1);
    }
}
