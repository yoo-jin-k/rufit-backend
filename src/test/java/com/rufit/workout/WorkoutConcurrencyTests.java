package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class WorkoutConcurrencyTests {

  @Autowired
  WorkoutRepository repository;

  @Autowired
  PlatformTransactionManager manager;

  @BeforeEach
  void clean() {
    repository.deleteAll();
  }

  @Test
  void anAssignedIdCannotMergeOverAnotherOwnersRecord() {
    UUID id = UUID.randomUUID();
    repository.saveAndFlush(new Workout(id, "alice", Instant.EPOCH));
    assertThatThrownBy(() ->
      repository.saveAndFlush(new Workout(id, "bob", Instant.EPOCH))
    ).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(repository.findById(id).orElseThrow().owner()).isEqualTo("alice");
  }

  @Test
  void concurrentWritersCannotOverwriteEachOther() throws Exception {
    UUID id = UUID.randomUUID();
    repository.saveAndFlush(new Workout(id, "concurrent-user", Instant.now()));
    CountDownLatch loaded = new CountDownLatch(2);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 1; i <= 2; i++) {
        final int reps = i;
        results.add(
          executor.submit(() -> {
            try {
              new TransactionTemplate(manager).executeWithoutResult(status -> {
                Workout workout = repository.findById(id).orElseThrow();
                loaded.countDown();
                try {
                  if (!loaded.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(
                    "Concurrent read timed out"
                  );
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(e);
                }
                workout.replaceEntries(List.of(new Workout.Entry("스쿼트", BigDecimal.TEN, reps)));
                repository.saveAndFlush(workout);
              });
              return true;
            } catch (OptimisticLockingFailureException expected) {
              return false;
            }
          })
        );
      }
      assertThat(
        List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS))
      ).containsExactlyInAnyOrder(true, false);
    }
    new TransactionTemplate(manager).executeWithoutResult(status -> {
      Workout saved = repository.findById(id).orElseThrow();
      assertThat(saved.entries()).hasSize(1);
      assertThat(saved.entries().getFirst().reps()).isIn(1, 2);
    });
  }
}
