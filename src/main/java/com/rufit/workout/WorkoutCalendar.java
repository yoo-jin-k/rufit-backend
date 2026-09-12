package com.rufit.workout;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class WorkoutCalendar {
    private final NamedParameterJdbcTemplate jdbc;
    public WorkoutCalendar(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    public MonthView month(String owner, String requestedMonth, String requestedZone) {
        YearMonth month;
        ZoneId zone;
        try {
            if (requestedMonth == null || !requestedMonth.matches("\\d{4}-\\d{2}")) throw new DateTimeException("Invalid month");
            month = YearMonth.parse(requestedMonth);
            if (month.getYear() < 1900 || month.getYear() > 2100) throw new DateTimeException("Year out of range");
            zone = ZoneId.of(requestedZone);
        } catch (DateTimeException | NullPointerException e) {
            throw new IllegalArgumentException("월(1900-01~2100-12)과 시간대를 확인해 주세요.");
        }
        Instant from = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant until = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        var sessions = jdbc.query("""
            SELECT w.started_at, w.finished_at, count(e.entry_order) AS sets,
                   coalesce(sum(e.weight * e.reps), 0) AS volume
            FROM workouts w
            LEFT JOIN completed_workout_sets c ON c.workout_id = w.id
            LEFT JOIN workout_entries e ON e.workout_id = c.workout_id AND e.entry_order = c.set_index
            WHERE w.owner_name = :owner AND w.finished_at IS NOT NULL
              AND w.started_at >= :from AND w.started_at < :until
            GROUP BY w.id, w.started_at, w.finished_at
            """, Map.of("owner", owner, "from", Timestamp.from(from), "until", Timestamp.from(until)),
            (row, index) -> new Session(row.getTimestamp("started_at").toInstant(), row.getTimestamp("finished_at").toInstant(),
                row.getLong("sets"), row.getBigDecimal("volume")));
        SortedMap<LocalDate, Day> days = new TreeMap<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            days.put(date, new Day(date, 0, 0, BigDecimal.ZERO, 0));
        }
        for (Session session : sessions) {
            LocalDate date = session.start.atZone(zone).toLocalDate();
            Day previous = days.get(date);
            days.put(date, new Day(date, previous.workouts + 1, previous.completedSets + session.sets,
                previous.volumeKgReps.add(session.volume), previous.durationSeconds + Duration.between(session.start, session.finish).getSeconds()));
        }
        List<Day> result = List.copyOf(days.values());
        return new MonthView(month.toString(), zone.getId(), result,
            result.stream().filter(day -> day.workouts > 0).count(), sessions.size(),
            result.stream().mapToLong(Day::completedSets).sum(),
            result.stream().map(Day::volumeKgReps).reduce(BigDecimal.ZERO, BigDecimal::add),
            result.stream().mapToLong(Day::durationSeconds).sum());
    }
    private record Session(Instant start, Instant finish, long sets, BigDecimal volume) {}
    @Schema(requiredProperties={"date", "workouts", "completedSets", "volumeKgReps", "durationSeconds"})
    public record Day(LocalDate date, long workouts, long completedSets, BigDecimal volumeKgReps, long durationSeconds) {}
    @Schema(requiredProperties={"month", "zone", "days", "workoutDays", "workouts", "completedSets", "volumeKgReps", "durationSeconds"})
    public record MonthView(String month, String zone, List<Day> days, long workoutDays, long workouts,
        long completedSets, BigDecimal volumeKgReps, long durationSeconds) {}
}
