package com.rufit.exercise;

import java.text.Normalizer;
import java.util.Locale;

/** FE 공용 목록과 같은 표기 차이를 흡수합니다. 개인 종목의 기존 원문 검색은 별도로 유지합니다. */
final class ExerciseSearchText {

  private ExerciseSearchText() {}

  static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
      .toLowerCase(Locale.ROOT)
      .replaceAll("[\\s\\p{Z}_\\-\uFEFF]+", "");
  }

  static String escape(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }
}
