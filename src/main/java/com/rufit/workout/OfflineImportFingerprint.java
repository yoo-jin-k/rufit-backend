package com.rufit.workout;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.rufit.workout.WorkoutController.OfflineImport;

/** Stable, length-delimited v1 encoding of the original request, before catalog/timer normalization. */
final class OfflineImportFingerprint {
    private OfflineImportFingerprint() {}
    static String of(OfflineImport request) {
        try {
            var bytes=new ByteArrayOutputStream();
            try (var out=new DataOutputStream(bytes)) {
                boolean hasPause=request.pausedAt()!=null || request.pausedDurationMs()!=null && request.pausedDurationMs()!=0;
                out.writeInt(hasPause ? 2 : 1);
                out.writeUTF(request.startedAt().toString());
                nullable(out,request.finishedAt());
                out.writeUTF(request.note());
                out.writeInt(request.sets().size());
                for (var row:request.sets()) {
                    var set=row.set();
                    nullable(out,set.exerciseId());out.writeUTF(set.exerciseName());
                    out.writeUTF(set.weight().stripTrailingZeros().toPlainString());
                    out.writeInt(set.reps());out.writeBoolean(row.completed());
                }
                nullable(out,request.restSetIndex());nullable(out,request.restEndsAt());
                if (hasPause) {nullable(out,request.pausedAt());out.writeLong(request.pausedDurationMs()==null?0:request.pausedDurationMs());}
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("가져오기 요청을 확인하지 못했습니다.",error);
        }
    }
    private static void nullable(DataOutputStream out,Object value) throws IOException {
        out.writeBoolean(value!=null);
        if (value!=null) out.writeUTF(value.toString());
    }
}
