package com.rufit.routine;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RetiredRoutineKeys {

  private final JdbcTemplate jdbc;

  RetiredRoutineKeys(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  boolean exists(UUID id) {
    return Boolean.TRUE.equals(
      jdbc.queryForObject(
        "select exists(select 1 from retired_routine_keys where id=?)",
        Boolean.class,
        id
      )
    );
  }

  boolean belongsTo(UUID id, String owner) {
    return Boolean.TRUE.equals(
      jdbc.queryForObject(
        "select exists(select 1 from retired_routine_keys where id=? and owner_name=?)",
        Boolean.class,
        id,
        owner
      )
    );
  }

  void retire(UUID id, String owner) {
    jdbc.update(
      "insert into retired_routine_keys(id,owner_name,deleted_at) values(?,?,current_timestamp)",
      id,
      owner
    );
  }
}
