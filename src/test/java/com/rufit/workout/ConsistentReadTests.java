package com.rufit.workout;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class ConsistentReadTests {
    @MockitoSpyBean WorkoutRepository repository;
    @Autowired WorkoutService service;
    @BeforeEach void clean() { repository.deleteAll(); }
    private Workout start() {
        Workout w=new Workout(UUID.randomUUID(),"alice",Instant.now());
        w.appendSet(new Workout.Entry("Squat",BigDecimal.TEN,10));
        return repository.saveAndFlush(w);
    }
    private void assertSnapshot(boolean replay) throws Exception {
        Workout w=start();
        var draft=new WorkoutController.Draft(UUID.randomUUID(),w.version(),
            List.of(new WorkoutController.DraftSet(new WorkoutController.SetInput("Squat",BigDecimal.TEN,10),true)),null,null,null);
        long revision=replay?service.saveDraft(w.id(),"alice",draft).workout().version():w.version();
        var rowLoaded=new CountDownLatch(1); var writerCommitted=new CountDownLatch(1);
        doAnswer(invocation -> {
            // The query-method interface has no real Java method to invoke on a spy.
            // Read the actual managed entity through the repository, then pause before lazy collections load.
            Object result=repository.findById((UUID)invocation.getArgument(0));
            if (Thread.currentThread().getName().equals("snapshot-reader")) {
                rowLoaded.countDown();
                if (!writerCommitted.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Writer did not commit");
            }
            return result;
        }).when(repository).findByIdAndOwnerName(w.id(),"alice");
        try (var pool=Executors.newSingleThreadExecutor(r -> new Thread(r,"snapshot-reader"))) {
            Future<WorkoutController.View> reading=pool.submit(() -> replay?service.saveDraft(w.id(),"alice",draft).workout():service.get(w.id(),"alice"));
            try {
                if (!rowLoaded.await(3,TimeUnit.SECONDS)) { reading.get(1,TimeUnit.SECONDS); throw new IllegalStateException("Read was not intercepted"); }
                service.editSet(w.id(),"alice",0,new WorkoutController.SetEdit(revision,new WorkoutController.SetInput("Squat",BigDecimal.valueOf(20),10)));
            } finally { writerCommitted.countDown(); }
            var snapshot=reading.get(15,TimeUnit.SECONDS);
            assertThat(snapshot.version()).isEqualTo(revision);
            assertThat(snapshot.sets().getFirst().weight()).isEqualByComparingTo("10");
            var current=service.get(w.id(),"alice");
            assertThat(current.version()).isGreaterThan(revision);
            assertThat(current.sets().getFirst().weight()).isEqualByComparingTo("20");
        }
    }
    @Test void detailVersionAndLazySetsComeFromOneDatabaseSnapshot() throws Exception { assertSnapshot(false); }
    @Test void replayVersionAndLazySetsComeFromOneDatabaseSnapshot() throws Exception { assertSnapshot(true); }
}
