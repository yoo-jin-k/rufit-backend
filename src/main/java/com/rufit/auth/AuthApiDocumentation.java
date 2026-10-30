package com.rufit.auth;

import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
@Profile("social")
class AuthApiDocumentation {

  /** 인증 요청의 크기/빈도 실패도 생성된 명세에 포함해 FE가 재시도 정책을 맞출 수 있게 합니다. */
  @Bean
  OpenApiCustomizer authErrorsContract() {
    return api ->
      api
        .getPaths()
        .forEach((url, path) -> {
          if (!url.startsWith("/api/v1/auth/")) return;
          path
            .readOperations()
            .forEach(operation ->
              Map.of("413", "인증 요청 본문 크기 초과", "429", "인증 요청 빈도 초과").forEach(
                (status, description) -> {
                  var response = new ApiResponse()
                    .description(description)
                    .content(
                      new Content().addMediaType(
                        "application/problem+json",
                        new MediaType().schema(
                          new Schema<>().$ref("#/components/schemas/ApiProblem")
                        )
                      )
                    );
                  response.addHeaderObject(
                    "X-Request-Id",
                    new Header().schema(new StringSchema().format("uuid"))
                  );
                  if (status.equals("429")) response.addHeaderObject(
                    "Retry-After",
                    new Header()
                      .description("다시 시도할 때까지 기다릴 정수 초")
                      .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE))
                  );
                  operation.getResponses().addApiResponse(status, response);
                }
              )
            );
        });
  }
}
