package com.rufit.workout;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentDraftApiTests {
    @Autowired MockMvc mvc;
    @Autowired WorkoutRepository repository;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clean() { repository.deleteAll(); }
    private Workout start() { return repository.saveAndFlush(new Workout(UUID.randomUUID(),"alice",Instant.now())); }
    private String draft(UUID operation,long version,int reps) {
        return "{\"operationId\":\""+operation+"\",\"version\":"+version+",\"sets\":[{\"set\":{\"exerciseName\":\"Squat\",\"weight\":40,\"reps\":"+reps+"},\"completed\":true}]}";
    }
    private MockHttpServletResponse save(UUID id,String body) throws Exception {
        return mvc.perform(put("/api/v1/workouts/"+id+"/draft").with(user("alice")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }
    private List<MockHttpServletResponse> race(List<Callable<MockHttpServletResponse>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<MockHttpServletResponse>>();
            for (var call : calls) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!ready.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent request startup timed out");
                return call.call();
            }));
            var results = new ArrayList<MockHttpServletResponse>();
            for (var future : futures) results.add(future.get(20,TimeUnit.SECONDS));
            return results;
        }
    }
    @Test void distinctConcurrentDraftsCommitExactlyOneCompleteSnapshot() throws Exception {
        Workout w = start();
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int reps=1; reps<=6; reps++) {
            String body=draft(UUID.randomUUID(),w.version(),reps);
            calls.add(() -> save(w.id(),body));
        }
        var results=race(calls);
        assertThat(results).allSatisfy(r -> assertThat(r.getStatus()).isIn(200,409));
        var winners=results.stream().filter(r -> r.getStatus()==200).toList();
        assertThat(winners).hasSize(1);
        Number expectedReps=JsonPath.read(winners.getFirst().getContentAsString(),"$.workout.sets[0].reps");
        String actual=mvc.perform(get("/api/v1/workouts/"+w.id()).with(user("alice"))).andReturn().getResponse().getContentAsString();
        assertThat((Integer)JsonPath.read(actual,"$.sets[0].reps")).isEqualTo(expectedReps.intValue());
        assertThat((List<Integer>)JsonPath.read(actual,"$.completedSetIndexes")).containsExactly(0);
        assertThat(jdbc.queryForObject("select count(*) from workout_draft_operations",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from workouts where id=?",Long.class,w.id())).isEqualTo(w.version()+1);
    }
    @Test void simultaneousRetriesNeverApplyOneOperationTwice() throws Exception {
        Workout w=start(); String body=draft(UUID.randomUUID(),w.version(),10);
        var results=race(Collections.nCopies(6,() -> save(w.id(),body)));
        assertThat(results).allSatisfy(r -> assertThat(r.getStatus()).isIn(200,409));
        assertThat(results).anySatisfy(r -> assertThat(r.getStatus()).isEqualTo(200));
        var retry=save(w.id(),body); assertThat(retry.getStatus()).isEqualTo(200);
        assertThat((Boolean)JsonPath.read(retry.getContentAsString(),"$.replayed")).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from workout_draft_operations",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from workouts where id=?",Long.class,w.id())).isEqualTo(w.version()+1);
    }
    @Test void simultaneousFinishAndCancellationHaveOnlyOneTerminalOutcome() throws Exception {
        Workout w=start(); var saved=save(w.id(),draft(UUID.randomUUID(),w.version(),10));
        Number version=JsonPath.read(saved.getContentAsString(),"$.workout.version");
        var results=race(List.of(
            () -> mvc.perform(post("/api/v1/workouts/"+w.id()+"/finish").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+"}")).andReturn().getResponse(),
            () -> mvc.perform(post("/api/v1/workouts/"+w.id()+"/cancel").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+",\"confirmed\":true}")).andReturn().getResponse()));
        assertThat(results.stream().map(MockHttpServletResponse::getStatus).toList()).containsExactlyInAnyOrder(200,409);
        String actual=mvc.perform(get("/api/v1/workouts/"+w.id()).with(user("alice"))).andReturn().getResponse().getContentAsString();
        Object finished=JsonPath.read(actual,"$.finishedAt"); Object cancelled=JsonPath.read(actual,"$.cancelledAt");
        assertThat((finished==null) != (cancelled==null)).isTrue();
    }
}
