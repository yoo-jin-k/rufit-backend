package com.rufit.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.*;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class ApiDocumentationConfig {

  @Bean
  OpenApiCustomizer errorContract() {
    return api -> {
      Schema<?> problem = new ObjectSchema()
        .addProperty("type", new StringSchema())
        .addProperty("title", new StringSchema())
        .addProperty("status", new IntegerSchema())
        .addProperty("detail", new StringSchema())
        .addProperty("code", new StringSchema())
        .addProperty("instance", new StringSchema());
      api.getComponents().addSchemas("ApiProblem", problem);
      api
        .getPaths()
        .values()
        .forEach(path ->
          path
            .readOperations()
            .forEach(operation -> {
              Map.of(
                "400",
                "입력 또는 요청 형식 오류",
                "401",
                "인증 필요",
                "403",
                "권한 또는 CSRF 오류",
                "404",
                "리소스 없음",
                "409",
                "버전·상태 충돌 또는 확인 필요",
                "410",
                "삭제한 기록의 재시도",
                "503",
                "인증 또는 기록 저장소의 일시적 장애"
              ).forEach((status, description) ->
                operation
                  .getResponses()
                  .putIfAbsent(
                    status,
                    new ApiResponse()
                      .description(description)
                      .content(
                        new Content().addMediaType(
                          "application/problem+json",
                          new MediaType().schema(
                            new Schema<>().$ref("#/components/schemas/ApiProblem")
                          )
                        )
                      )
                  )
              );
              operation
                .getResponses()
                .values()
                .forEach(response ->
                  response.addHeaderObject(
                    "X-Request-Id",
                    new io.swagger.v3.oas.models.headers.Header()
                      .description("Server-generated request identifier")
                      .schema(new StringSchema().format("uuid"))
                  )
                );
            })
        );
    };
  }

  @Bean
  OpenAPI rufitApi(Environment environment) {
    boolean oidc = environment.acceptsProfiles(Profiles.of("oidc", "social"));
    String scheme = oidc ? "bearerAuth" : "developmentBasic";
    SecurityScheme security = new SecurityScheme()
      .type(SecurityScheme.Type.HTTP)
      .scheme(oidc ? "bearer" : "basic");
    if (oidc) security.bearerFormat("JWT");
    return new OpenAPI()
      .info(
        new Info()
          .title("루핏 API")
          .version("0.1.0")
          .description(
            oidc
              ? "API용 액세스 토큰이 필요합니다. 소유권은 인증 정보에서 결정합니다."
              : "개발용 Basic 인증입니다. 쓰기 요청 전 GET /api/v1/csrf에서 토큰을 받고 동일 세션의 응답 headerName/token을 요청 헤더로 보냅니다."
          )
      )
      .components(new Components().addSecuritySchemes(scheme, security))
      .addSecurityItem(new SecurityRequirement().addList(scheme));
  }
}
