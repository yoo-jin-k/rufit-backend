package com.rufit.workout;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkoutCalendarTests {
    @Autowired MockMvc mvc;
    @Autowired WorkoutRepository repository;
    @BeforeEach void clean() { repository.deleteAll(); }
    private void save(String owner, String time, String state) {
        Instant start = Instant.parse(time);
        Workout w = new Workout(UUID.randomUUID(), owner, start);
        w.appendSet(new Workout.Entry("스쿼트", new BigDecimal("40.50"), 10));
        w.appendSet(new Workout.Entry("푸시업", BigDecimal.ZERO, 8));
        w.completeSet(0, true, 0, start);
        w.completeSet(1, true, 0, start);
        if (state.equals("finished")) w.finish(start.plusSeconds(600));
        if (state.equals("cancelled")) w.cancel(start.plusSeconds(60));
        repository.saveAndFlush(w);
    }
    @Test void localMonthBoundaryAggregationExcludesOtherOwnersAndUnfinishedSessions() throws Exception {
        save("alice", "2026-08-31T14:59:59Z", "finished");
        save("alice", "2026-08-31T15:00:00Z", "finished");
        save("alice", "2026-09-01T01:00:00Z", "finished");
        save("alice", "2026-09-30T15:00:00Z", "finished");
        save("bob", "2026-09-01T01:00:00Z", "finished");
        save("alice", "2026-09-02T01:00:00Z", "cancelled");
        save("alice", "2026-09-03T01:00:00Z", "active");
        mvc.perform(get("/api/v1/workouts/calendar").param("month", "2026-09").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.zone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.days.length()").value(30)).andExpect(jsonPath("$.workouts").value(2))
            .andExpect(jsonPath("$.workoutDays").value(1)).andExpect(jsonPath("$.completedSets").value(4))
            .andExpect(jsonPath("$.volumeKgReps").value(810)).andExpect(jsonPath("$.durationSeconds").value(1200))
            .andExpect(jsonPath("$.days[0].workouts").value(2)).andExpect(jsonPath("$.days[1].workouts").value(0));
    }
    @Test void weekCrossesMonthBoundaryAndExcludesOutsideAndOtherOwners() throws Exception {
        save("alice","2026-08-30T14:59:59Z","finished");
        save("alice","2026-08-30T15:00:00Z","finished");
        save("alice","2026-09-06T14:59:59Z","finished");
        save("alice","2026-09-06T15:00:00Z","finished");
        save("bob","2026-09-01T01:00:00Z","finished");
        save("alice","2026-09-02T01:00:00Z","cancelled");
        save("alice","2026-09-03T01:00:00Z","active");
        mvc.perform(get("/api/v1/workouts/calendar/week").param("date","2026-09-01").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.weekStart").value("2026-08-31"))
            .andExpect(jsonPath("$.weekEnd").value("2026-09-06")).andExpect(jsonPath("$.days.length()").value(7))
            .andExpect(jsonPath("$.days[0].workouts").value(1)).andExpect(jsonPath("$.days[6].workouts").value(1))
            .andExpect(jsonPath("$.workouts").value(2)).andExpect(jsonPath("$.workoutDays").value(2))
            .andExpect(jsonPath("$.completedSets").value(4)).andExpect(jsonPath("$.volumeKgReps").value(810))
            .andExpect(jsonPath("$.durationSeconds").value(1200));
    }
    @Test void weekWithDaylightSavingUsesActualLocalDateBoundaries() throws Exception {
        save("alice","2026-03-02T05:00:00Z","finished");
        save("alice","2026-03-09T03:59:59Z","finished");
        save("alice","2026-03-09T04:00:00Z","finished");
        mvc.perform(get("/api/v1/workouts/calendar/week").param("date","2026-03-08").param("zone","America/New_York").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.workouts").value(2))
            .andExpect(jsonPath("$.weekStart").value("2026-03-02")).andExpect(jsonPath("$.weekEnd").value("2026-03-08"));
    }
    @Test void emptyWeekAcrossYearBoundaryAndInvalidSelection() throws Exception {
        mvc.perform(get("/api/v1/workouts/calendar/week").param("date","2026-01-01").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.weekStart").value("2025-12-29"))
            .andExpect(jsonPath("$.weekEnd").value("2026-01-04")).andExpect(jsonPath("$.workouts").value(0))
            .andExpect(jsonPath("$.days.length()").value(7));
        mvc.perform(get("/api/v1/workouts/calendar/week").param("date","2026-02-29").with(user("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workouts/calendar/week").param("date","2026-09-01").param("zone","invalid").with(user("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workouts/calendar/week").param("date","2026-09-01")).andExpect(status().isUnauthorized());
    }
    @Test void utcAndLeapMonthAndInvalidParameters() throws Exception {
        save("alice", "2024-02-29T23:30:00Z", "finished");
        mvc.perform(get("/api/v1/workouts/calendar").param("month", "2024-02").param("zone", "UTC").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.days.length()").value(29))
            .andExpect(jsonPath("$.days[28].workouts").value(1));
        mvc.perform(get("/api/v1/workouts/calendar").param("month", "2024-02").with(user("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.workouts").value(0));
        for (String month : new String[]{"2026-13", "26-01", "0000-01"})
            mvc.perform(get("/api/v1/workouts/calendar").param("month", month).with(user("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workouts/calendar").param("month", "2026-09").param("zone", "invalid").with(user("alice"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/workouts/calendar").param("month", "2026-09")).andExpect(status().isUnauthorized());
    }
}
