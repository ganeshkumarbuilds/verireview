package com.verireview.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** OpenAPI 3 documentation (API_DESIGN §1). Secured by JWT Bearer. */
@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI openAPI(
      @Value("${spring.application.name:VeriReview}") String appName,
      @Value("${server.port:8080}") String port) {
    return new OpenAPI()
        .info(new Info()
            .title(appName + " API")
            .version("0.1.0")
            .description("VeriReview backend API. All endpoints return the standard envelope: { data, error, traceId }.")
            .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
        .servers(List.of(new Server().url("http://localhost:" + port).description("Local development")))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
        .components(new Components()
            .addSecuritySchemes("bearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("JWT access token from /api/v1/auth/login or /api/v1/auth/refresh")));
  }
}