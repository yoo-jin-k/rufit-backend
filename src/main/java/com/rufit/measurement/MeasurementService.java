package com.rufit.measurement;

import com.rufit.workout.WorkoutConflict;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 날짜·중량 검증과 버전 비교를 같은 트랜잭션에서 처리합니다. 자동 덮어쓰기는 하지 않습니다. */
@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class MeasurementService {

  private final JdbcTemplate jdbc;

  MeasurementService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static final String COLUMNS = "record_date,version,weight,bench,squat,deadlift";
  private static final RowMapper<MeasurementController.View> ROW = (rs, row) ->
    new MeasurementController.View(
      rs.getObject(1, LocalDate.class),
      rs.getLong(2),
      rs.getBigDecimal(3),
      rs.getBigDecimal(4),
      rs.getBigDecimal(5),
      rs.getBigDecimal(6)
    );

  private static void kind(String kind) {
    if (!List.of("weight", "strength").contains(kind)) throw new IllegalArgumentException(
      "측정 종류를 확인해 주세요."
    );
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public MeasurementController.Page list(String owner, String kind, int page, int size) {
    kind(kind);
    if (page < 0 || page > 10000 || size < 1 || size > 100) throw new IllegalArgumentException(
      "조회 범위를 확인해 주세요."
    );
    var rows = jdbc.query(
      "select " +
        COLUMNS +
        " from measurements where owner_name=? and kind=? order by record_date desc limit ? offset ?",
      ROW,
      owner,
      kind,
      size,
      page * size
    );
    Long total = jdbc.queryForObject(
      "select count(*) from measurements where owner_name=? and kind=?",
      Long.class,
      owner,
      kind
    );
    return new MeasurementController.Page(
      rows,
      page,
      size,
      total,
      (long) (page + 1) * size < total
    );
  }

  private MeasurementController.View get(String owner, String kind, LocalDate date) {
    return jdbc
      .query(
        "select " + COLUMNS + " from measurements where owner_name=? and kind=? and record_date=?",
        ROW,
        owner,
        kind,
        date
      )
      .stream()
      .findFirst()
      .orElse(null);
  }

  private static void value(BigDecimal value, int maximum) {
    if (
      value == null ||
      value.signum() <= 0 ||
      value.compareTo(BigDecimal.valueOf(maximum)) > 0 ||
      value.stripTrailingZeros().scale() > 2
    ) throw new IllegalArgumentException("중량 범위와 소수 둘째 자리까지의 입력을 확인해 주세요.");
  }

  private static boolean same(BigDecimal a, BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }

  public MeasurementController.View save(
    String owner,
    String kind,
    LocalDate date,
    MeasurementController.Edit edit
  ) {
    kind(kind);
    if (
      date == null ||
      date.isBefore(LocalDate.of(1900, 1, 1)) ||
      date.isAfter(LocalDate.now(ZoneId.of("Asia/Seoul"))) ||
      edit.version() == null ||
      edit.version() < 0
    ) throw new IllegalArgumentException("오늘 또는 이전 날짜와 기록 버전을 확인해 주세요.");
    if (kind.equals("weight")) {
      value(edit.weight(), 700);
      if (
        edit.bench() != null || edit.squat() != null || edit.deadlift() != null
      ) throw new IllegalArgumentException("체중만 입력해 주세요.");
    } else {
      value(edit.bench(), 1000);
      value(edit.squat(), 1000);
      value(edit.deadlift(), 1000);
      if (edit.weight() != null) throw new IllegalArgumentException("삼대 중량만 입력해 주세요.");
    }
    var current = get(owner, kind, date);
    // 응답 유실 후 같은 값을 다시 보내도 버전을 불필요하게 증가시키지 않습니다.
    if (
      current != null &&
      same(current.weight(), edit.weight()) &&
      same(current.bench(), edit.bench()) &&
      same(current.squat(), edit.squat()) &&
      same(current.deadlift(), edit.deadlift())
    ) return current;
    int changed;
    if (edit.version() == 0) changed = jdbc.update(
      "insert into measurements(owner_name,kind,record_date,version,weight,bench,squat,deadlift) values(?,?,?,1,?,?,?,?) on conflict do nothing",
      owner,
      kind,
      date,
      edit.weight(),
      edit.bench(),
      edit.squat(),
      edit.deadlift()
    );
    else changed = jdbc.update(
      "update measurements set weight=?,bench=?,squat=?,deadlift=?,version=version+1 where owner_name=? and kind=? and record_date=? and version=?",
      edit.weight(),
      edit.bench(),
      edit.squat(),
      edit.deadlift(),
      owner,
      kind,
      date,
      edit.version()
    );
    if (changed != 1) throw new WorkoutConflict(
      "측정값이 변경되었습니다. 다시 비교한 뒤 사용할 값을 선택해 주세요."
    );
    return get(owner, kind, date);
  }
}
