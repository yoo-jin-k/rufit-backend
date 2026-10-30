package com.rufit.workout;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class HistoryQueryBatchingTests {

  @Autowired
  WorkoutRepository repository;

  @Autowired
  WorkoutService service;

  @Autowired
  EntityManagerFactory entityManagerFactory;

  @BeforeEach
  void seed() {
    repository.deleteAll();
    List<Workout> records = new ArrayList<>();
    for (int i = 0; i < 45; i++) {
      Instant now = Instant.parse("2026-09-01T01:00:00Z").plusSeconds(i);
      Workout w = new Workout(UUID.randomUUID(), i < 40 ? "batch-alice" : "batch-bob", now);
      w.appendSet(new Workout.Entry("스쿼트", BigDecimal.valueOf(i + 1), 5));
      w.appendSet(new Workout.Entry("푸시업", BigDecimal.ZERO, 10));
      w.completeSet(0, true, 0, now);
      w.completeSet(1, true, 0, now);
      w.finish(now.plusSeconds(600));
      records.add(w);
    }
    repository.saveAllAndFlush(records);
  }

  @Test
  void paginatedHistoryLoadsCollectionsInBatchesWithoutChangingOrderOrOwner() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean enabled = stats.isStatisticsEnabled();
    stats.setStatisticsEnabled(true);
    try {
      stats.clear();
      var first = service.history("batch-alice", 0, 20);
      long firstQueries = stats.getPrepareStatementCount();
      assertThat(first.items()).hasSize(20);
      assertThat(first.totalElements()).isEqualTo(40);
      assertThat(first.items().getFirst().sets().getFirst().weight()).isEqualByComparingTo("40");
      assertThat(first.items().getLast().sets().getFirst().weight()).isEqualByComparingTo("21");
      assertThat(first.items()).allSatisfy(w ->
        assertThat(w.completedSetIndexes()).containsExactly(0, 1)
      );
      assertThat(firstQueries)
        .as("20 records must not issue one collection query per workout")
        .isLessThanOrEqualTo(8);
      stats.clear();
      var second = service.history("batch-alice", 1, 20, "2026-09-01", "Asia/Seoul");
      long dailyQueries = stats.getPrepareStatementCount();
      assertThat(second.items()).hasSize(20);
      assertThat(second.hasNext()).isFalse();
      assertThat(second.items().getFirst().sets().getFirst().weight()).isEqualByComparingTo("20");
      assertThat(second.items().getLast().sets().getFirst().weight()).isEqualByComparingTo("1");
      assertThat(dailyQueries).as("daily history query count").isLessThanOrEqualTo(8);
      stats.clear();
      var exercise = service.exerciseHistory("batch-alice", "스쿼트", null, 0, 20);
      long exerciseQueries = stats.getPrepareStatementCount();
      assertThat(exercise.items()).hasSize(20);
      assertThat(exercise.totalElements()).isEqualTo(40);
      assertThat(exercise.items()).allSatisfy(w -> assertThat(w.sets()).hasSize(1));
      assertThat(exerciseQueries).as("exercise history query count").isLessThanOrEqualTo(8);
      System.out.printf(
        "History query counts: all=%d daily=%d exercise=%d%n",
        firstQueries,
        dailyQueries,
        exerciseQueries
      );
    } finally {
      stats.setStatisticsEnabled(enabled);
      stats.clear();
    }
  }
}
