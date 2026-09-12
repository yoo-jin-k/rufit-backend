package com.rufit.workout;

import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/workouts/calendar")
public class WorkoutCalendarController {
    private final WorkoutCalendar calendar;
    public WorkoutCalendarController(WorkoutCalendar calendar) { this.calendar = calendar; }
    @GetMapping("/week")
    public WorkoutCalendar.WeekView week(@RequestParam String date,
        @RequestParam(defaultValue="Asia/Seoul") String zone,Principal principal) {
        return calendar.week(principal.getName(),date,zone);
    }
    @GetMapping
    public WorkoutCalendar.MonthView month(@RequestParam String month,
        @RequestParam(defaultValue="Asia/Seoul") String zone, Principal principal) {
        return calendar.month(principal.getName(), month, zone);
    }
}
