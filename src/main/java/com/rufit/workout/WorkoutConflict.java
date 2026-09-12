package com.rufit.workout;
public class WorkoutConflict extends RuntimeException {
    private final String code;
    public WorkoutConflict(String message) { this("STATE_CONFLICT", message); }
    public WorkoutConflict(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
