package com.rufit.workout;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class RestAdjustmentTests {
    @Autowired MockMvc mvc;
    @Autowired WorkoutRepository repository;
    @BeforeEach void clean() {repository.deleteAll();}
    private Workout workout(Instant start) {
        Workout w=new Workout(UUID.randomUUID(),"alice",start);w.appendSet(new Workout.Entry("스쿼트",BigDecimal.ONE,2));
        w.completeSet(0,true,90,start);return w;
    }
    @Test void adjustsTenSecondsWithoutChangingCompletedSetOrPausedWorkoutClock() {
        Instant start=Instant.EPOCH;Workout w=workout(start);w.pauseTimer(start.plusSeconds(1));
        w.adjustRest(10,start.plusSeconds(10));assertThat(w.restEndsAt()).isEqualTo(start.plusSeconds(100));
        w.adjustRest(-10,start.plusSeconds(11));assertThat(w.restEndsAt()).isEqualTo(start.plusSeconds(90));
        assertThat(w.completedSetIndexes()).containsExactly(0);assertThat(w.restSetIndex()).isEqualTo(0);
        assertThat(w.pausedAt()).isEqualTo(start.plusSeconds(1));assertThat(w.pausedDurationMs()).isZero();
    }
    @Test void expiredRestCanExtendFromNowAndIsClampedBetweenZeroAndOneHour() {
        Workout w=workout(Instant.EPOCH);w.adjustRest(10,Instant.EPOCH.plusSeconds(1000));
        assertThat(w.restEndsAt()).isEqualTo(Instant.EPOCH.plusSeconds(1010));
        w.adjustRest(-10,Instant.EPOCH.plusSeconds(1005));assertThat(w.restEndsAt()).isEqualTo(Instant.EPOCH.plusSeconds(1005));
        w.completeSet(0,false,0,Instant.EPOCH.plusSeconds(1005));w.completeSet(0,true,3600,Instant.EPOCH.plusSeconds(1005));
        w.adjustRest(10,Instant.EPOCH.plusSeconds(1005));assertThat(w.restEndsAt()).isEqualTo(Instant.EPOCH.plusSeconds(4605));
        assertThatThrownBy(()->w.adjustRest(30,Instant.now())).isInstanceOf(IllegalArgumentException.class);
        w.skipRest();assertThatThrownBy(()->w.adjustRest(10,Instant.now())).isInstanceOf(WorkoutConflict.class);
    }
    @Test void duplicateStaleAdjustmentsDoNotApplyTwiceAndOtherOwnersCannotAdjust() throws Exception {
        Instant start=Instant.now();Workout w=repository.saveAndFlush(workout(start));
        String body="{\"version\":0,\"seconds\":10}";String path="/api/v1/workouts/"+w.id()+"/rest/adjust";
        mvc.perform(post(path).with(user("bob")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNotFound());
        String response=mvc.perform(post(path).with(user("alice")).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andReturn().getResponse().getContentAsString();
        String end=JsonPath.read(response,"$.restEndsAt");
        mvc.perform(post(path).with(user("alice")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/workouts/"+w.id()).with(user("alice"))).andExpect(jsonPath("$.restEndsAt").value(end));
        for(String invalid:new String[]{"{}","{\"version\":1}","{\"version\":1,\"seconds\":30}","{\"version\":1,\"seconds\":10.1}","{\"version\":1,\"seconds\":\"10\"}"})
            mvc.perform(post(path).with(user("alice")).with(csrf()).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/workouts/"+w.id()+"/finish").with(user("alice")).with(csrf()).contentType("application/json")
            .content("{\"version\":1}" )).andExpect(status().isOk());
        mvc.perform(post(path).with(user("alice")).with(csrf()).contentType("application/json").content("{\"version\":2,\"seconds\":10}" )).andExpect(status().isConflict());
    }
}
