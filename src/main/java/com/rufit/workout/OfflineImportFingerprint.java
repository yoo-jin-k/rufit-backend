package com.rufit.workout;

import com.rufit.workout.WorkoutController.OfflineImport;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable, length-delimited v1 encoding of the original request, before catalog/timer normalization. */
final class OfflineImportFingerprint {

  private OfflineImportFingerprint() {}

  static String of(OfflineImport request) {
    try {
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        boolean hasPause =
          request.pausedAt() != null ||
          (request.pausedDurationMs() != null && request.pausedDurationMs() != 0);
        out.writeInt((request.recordDate() != null || request.entryMode() != null) ? 3 : hasPause ? 2 : 1);
        out.writeUTF(request.startedAt().toString());
        nullable(out, request.finishedAt());
        out.writeUTF(request.note());
        out.writeInt(request.sets().size());
        for (var row : request.sets()) {
          var set = row.set();
          nullable(out, set.exerciseId());
          out.writeUTF(set.exerciseName());
          out.writeUTF(set.weight().stripTrailingZeros().toPlainString());
          out.writeInt(set.reps() == null ? 0 : set.reps());
          out.writeBoolean(row.completed());
          // 세트 종류·자각도가 있을 때만 더해, 이 값이 없던 예전 요청의 지문은 그대로 유지합니다.
          boolean detailed = (set.setType() != null && !"NORMAL".equals(set.setType())) || set.rpe() != null;
          if (detailed) {
            out.writeUTF("set-detail");
            out.writeUTF(set.setType() == null ? "NORMAL" : set.setType());
            out.writeInt(set.rpe() == null ? 0 : set.rpe());
          }
          // 시간 세트일 때만 더해, 예전 요청의 지문은 그대로 유지합니다.
          if (set.durationSeconds() != null) {
            out.writeUTF("duration");
            out.writeInt(set.durationSeconds());
          }
        }
        nullable(out, request.restSetIndex());
        nullable(out, request.restEndsAt());
        if (hasPause || (request.recordDate() != null || request.entryMode() != null)) {
          nullable(out, request.pausedAt());
          out.writeLong(request.pausedDurationMs() == null ? 0 : request.pausedDurationMs());
        }
        if ((request.recordDate() != null || request.entryMode() != null)) { nullable(out, request.recordDate()); nullable(out, request.entryMode()); }
      }
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
      );
    } catch (IOException | NoSuchAlgorithmException error) {
      throw new IllegalStateException("가져오기 요청을 확인하지 못했습니다.", error);
    }
  }

  private static void nullable(DataOutputStream out, Object value) throws IOException {
    out.writeBoolean(value != null);
    if (value != null) out.writeUTF(value.toString());
  }
}
