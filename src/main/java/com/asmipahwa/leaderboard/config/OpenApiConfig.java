package com.asmipahwa.leaderboard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title and description for the Swagger UI at {@code /swagger-ui.html}. */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI leaderboardOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Game Stats & Leaderboard Service")
                .version("v1")
                .description(
                    "REST facade over the gRPC API (leaderboard.v1). Game clients should prefer"
                        + " gRPC; these endpoints exist for browsers, tools and demos.")
                .license(new License().name("MIT")));
  }
}
