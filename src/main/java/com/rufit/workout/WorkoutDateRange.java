package com.rufit.workout;

import java.time.*;

record WorkoutDateRange(Instant from,Instant until) {
    static WorkoutDateRange day(String requestedDate,String requestedZone) {
        try {
            if (requestedDate==null || !requestedDate.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeException("Invalid date");
            LocalDate date=LocalDate.parse(requestedDate);
            if (date.getYear()<1900 || date.getYear()>2100) throw new DateTimeException("Year out of range");
            ZoneId zone=ZoneId.of(requestedZone);
            return new WorkoutDateRange(date.atStartOfDay(zone).toInstant(),date.plusDays(1).atStartOfDay(zone).toInstant());
        } catch (DateTimeException | NullPointerException e) {
            throw new IllegalArgumentException("날짜(1900-01-01~2100-12-31)와 시간대를 확인해 주세요.");
        }
    }
}
