package com.rufit.measurement;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 측정 기록은 로그인한 계정의 날짜별 값으로만 조회·수정합니다. */
@RestController
@RequestMapping("/api/v1/measurements/{kind}")
public class MeasurementController {

  private final MeasurementService service;

  MeasurementController(MeasurementService service) {
    this.service = service;
  }

  @GetMapping
  public Page list(
    @PathVariable String kind,
    @RequestParam(defaultValue = "0") int page,
    @RequestParam(defaultValue = "100") int size,
    Principal principal
  ) {
    return service.list(principal.getName(), kind, page, size);
  }

  @PutMapping("/{date}")
  public View save(
    @PathVariable String kind,
    @PathVariable LocalDate date,
    @Valid @RequestBody Edit edit,
    Principal principal
  ) {
    return service.save(principal.getName(), kind, date, edit);
  }

  @Schema(name = "MeasurementEdit")
  public record Edit(
    @NotNull @PositiveOrZero Long version,
    BigDecimal weight,
    BigDecimal bench,
    BigDecimal squat,
    BigDecimal deadlift
  ) {}

  @Schema(
    name = "MeasurementView",
    requiredProperties = { "date", "version", "weight", "bench", "squat", "deadlift" }
  )
  public record View(
    LocalDate date,
    long version,
    @Schema(nullable = true) BigDecimal weight,
    @Schema(nullable = true) BigDecimal bench,
    @Schema(nullable = true) BigDecimal squat,
    @Schema(nullable = true) BigDecimal deadlift
  ) {}

  @Schema(
    name = "MeasurementPage",
    requiredProperties = { "items", "page", "size", "totalElements", "hasNext" }
  )
  public record Page(List<View> items, int page, int size, long totalElements, boolean hasNext) {}
}
