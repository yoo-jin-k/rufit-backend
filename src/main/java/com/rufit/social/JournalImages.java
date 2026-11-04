package com.rufit.social;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** 확장자만 믿지 않고 크기를 먼저 확인한 뒤 재인코딩하여 위치 등 사진 메타데이터를 제거합니다. */
final class JournalImages {

  private JournalImages() {}

  static byte[] normalize(String source, int maxEdge) {
    if (
      source == null ||
      source.length() > 2_800_000 ||
      !(source.startsWith("data:image/jpeg;base64,") || source.startsWith("data:image/png;base64,"))
    ) throw new IllegalArgumentException("사진은 2MB 이하 JPG 또는 PNG로 선택해 주세요.");
    try {
      byte[] bytes = Base64.getDecoder().decode(source.substring(source.indexOf(',') + 1));
      if (bytes.length > 2_097_152) throw new IllegalArgumentException("사진 용량이 너무 큽니다.");
      try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) throw new IllegalArgumentException("사진 형식을 읽을 수 없습니다.");
        var reader = readers.next();
        try {
          reader.setInput(stream, true, true);
          String format = reader.getFormatName();
          if (
            !format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG")
          ) throw new IllegalArgumentException("JPG 또는 PNG 사진만 사용할 수 있습니다.");
          int w = reader.getWidth(0),
            h = reader.getHeight(0);
          if (w <= 0 || h <= 0 || (long) w * h > 16_000_000) throw new IllegalArgumentException(
            "사진 해상도를 1600만 화소 이하로 줄여 주세요."
          );
          BufferedImage image = reader.read(0);
          double scale = Math.min(1, (double) maxEdge / Math.max(w, h));
          var result = new BufferedImage(
            Math.max(1, (int) (w * scale)),
            Math.max(1, (int) (h * scale)),
            BufferedImage.TYPE_INT_RGB
          );
          var g = result.createGraphics();
          try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, result.getWidth(), result.getHeight());
            g.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION,
              RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            g.drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null);
          } finally {
            g.dispose();
          }
          var out = new ByteArrayOutputStream();
          ImageIO.write(result, "jpg", out);
          return out.toByteArray();
        } finally {
          reader.dispose();
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("사진을 처리하지 못했습니다.");
    }
  }

  static String uri(byte[] value) {
    return value == null
      ? null
      : "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(value);
  }
}
