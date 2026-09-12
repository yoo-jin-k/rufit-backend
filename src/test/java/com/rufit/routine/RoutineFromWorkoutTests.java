package com.rufit.routine;

import com.rufit.workout.*;
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

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class RoutineFromWorkoutTests {
    @Autowired MockMvc mvc;
    @Autowired RoutineRepository repository;
    @Autowired RoutineService routines;
    @Autowired WorkoutService workouts;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.rufit.exercise.ExerciseService catalog;
    @BeforeEach void clean() { jdbc.update("delete from workouts");repository.deleteAll();jdbc.update("delete from retired_routine_keys"); }
    private WorkoutController.View finished() {
        var w=workouts.start("alice");
        w=workouts.appendSet(w.id(),"alice",new WorkoutController.SetEdit(w.version(),new WorkoutController.SetInput("Free squat",BigDecimal.TEN,5)));
        w=workouts.complete(w.id(),"alice",0,new WorkoutController.Completion(w.version(),true,60));
        return workouts.finish(w.id(),"alice",w.version(),false);
    }
    private String body(long version) { return "{\"version\":"+version+",\"title\":\"From record\"}"; }
    @Test void snapshotsCompletedWorkoutAndRetrySurvivesSourceDeletion() throws Exception {
        var source=finished();UUID key=UUID.randomUUID();
        mvc.perform(post("/api/v1/routines/from-workout/"+source.id()).with(user("alice")).with(csrf())
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body(source.version())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(key.toString()))
            .andExpect(jsonPath("$.sourceWorkoutId").value(source.id().toString())).andExpect(jsonPath("$.sourceRoutineId").isEmpty())
            .andExpect(jsonPath("$.sets[0].weight").value(10));
        var routine=routines.get(key,"alice");
        routine=routines.update(key,"alice",new RoutineController.Edit(routine.version(),"Changed copy",List.of(new WorkoutController.SetInput("Press",BigDecimal.valueOf(30),8))));
        assertThat(workouts.get(source.id(),"alice").sets().getFirst().weight()).isEqualByComparingTo("10");
        workouts.deleteHistory(source.id(),"alice",source.version());
        mvc.perform(post("/api/v1/routines/from-workout/"+source.id()).with(user("alice")).with(csrf())
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body(source.version())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("Changed copy"));
        var started=routines.start(key,"alice",UUID.randomUUID());
        assertThat(started.sets().getFirst().weight()).isEqualByComparingTo("30");
        assertThat(started.completedSetIndexes()).isEmpty();assertThat(started.restEndsAt()).isNull();
        routines.delete(key,"alice",routine.version());
        mvc.perform(post("/api/v1/routines/from-workout/"+source.id()).with(user("alice")).with(csrf())
            .header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body(source.version()))).andExpect(status().isGone());
    }
    @Test void archivedExerciseRetainsItsHistoricalNameAndIdentityInNewRoutine() {
        UUID exerciseId=UUID.randomUUID();
        var exercise=catalog.create("alice",exerciseId,new com.rufit.exercise.ExerciseController.Create("Snapshot "+exerciseId,
            com.rufit.exercise.Exercise.BodyPart.LEGS,com.rufit.exercise.Exercise.Equipment.BARBELL));
        var w=workouts.start("alice");
        w=workouts.appendSet(w.id(),"alice",new WorkoutController.SetEdit(w.version(),new WorkoutController.SetInput("ignored",BigDecimal.TEN,5,exerciseId)));
        w=workouts.complete(w.id(),"alice",0,new WorkoutController.Completion(w.version(),true,0));
        w=workouts.finish(w.id(),"alice",w.version(),false);
        String snapshotName=exercise.name();
        exercise=catalog.update(exerciseId,"alice",new com.rufit.exercise.ExerciseController.Edit(exercise.version(),"Renamed "+exerciseId,
            exercise.bodyPart(),exercise.equipment()));
        catalog.archive(exerciseId,"alice",exercise.version());
        var routine=routines.fromWorkout(w.id(),"alice",new RoutineController.Copy(w.version(),"Archived snapshot"),UUID.randomUUID());
        assertThat(routine.sets().getFirst().exerciseId()).isEqualTo(exerciseId);
        assertThat(routine.sets().getFirst().exerciseName()).isEqualTo(snapshotName);
        var next=routines.start(routine.id(),"alice",UUID.randomUUID());
        assertThat(next.sets().getFirst().exerciseId()).isEqualTo(exerciseId);
        assertThat(next.sets().getFirst().exerciseName()).isEqualTo(snapshotName);
    }
    @Test void checksOwnerVersionStateAndPreventsCrossOperationReplay() throws Exception {
        var source=finished();
        mvc.perform(post("/api/v1/routines/from-workout/"+source.id()).with(user("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(source.version())))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/routines/from-workout/"+source.id()).with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(source.version()+1)))
            .andExpect(status().isConflict());
        var active=workouts.start("alice");
        mvc.perform(post("/api/v1/routines/from-workout/"+active.id()).with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(active.version())))
            .andExpect(status().isConflict());
        active=workouts.cancel(active.id(),"alice",active.version());
        mvc.perform(post("/api/v1/routines/from-workout/"+active.id()).with(user("alice")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(active.version())))
            .andExpect(status().isConflict());
        UUID key=UUID.randomUUID();
        var created=routines.fromWorkout(source.id(),"alice",new RoutineController.Copy(source.version(),"Copy"),key);
        assertThatThrownBy(() -> routines.create("alice",new RoutineController.Create("Other",created.sets()),key)).isInstanceOf(WorkoutConflict.class);
        assertThatThrownBy(() -> routines.copy(key,"alice",new RoutineController.Copy(created.version(),"Other"),key)).isInstanceOf(WorkoutConflict.class);
        assertThatThrownBy(() -> routines.fromWorkout(UUID.randomUUID(),"alice",new RoutineController.Copy(source.version(),"Other"),key)).isInstanceOf(WorkoutConflict.class);
        mvc.perform(post("/api/v1/routines/from-workout/"+source.id()).contentType(MediaType.APPLICATION_JSON).content(body(source.version())))
            .andExpect(status().isForbidden());
        assertThat(repository.count()).isEqualTo(1);
    }
}
