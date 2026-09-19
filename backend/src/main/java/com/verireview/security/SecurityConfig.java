package com.verireview.security;

import com.verireview.common.AuthRateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Phase 3 security chain (SECURITY_DESIGN §2): stateless JWT. Public:
 * {@code /api/v1/health} plus the credential flows
 * ({@code /api/v1/auth/register}, {@code /login}, {@code /refresh},
 * {@code /password-reset/request}).
 * {@code /api/v1/auth/me} and {@code /logout} require authentication;
 * {@code /api/v1/users/**} and {@code /api/v1/admin/**} are ADMIN-only;
 * everything else is authenticated. Unauthenticated requests answer 401
 * (never a redirect); authenticated-but-forbidden answers 403. Method
 * security is enabled as the second RBAC layer (belt and suspenders).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  /** BCrypt strength 12 per SECURITY_DESIGN §2. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      JwtAuthFilter jwtAuthFilter,
      AuthRateLimitFilter rateLimitFilter,
      org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource)
      throws Exception {
    http.csrf(csrf -> csrf.disable());
    http.cors(cors -> cors.configurationSource(corsConfigurationSource));
    http.sessionManagement(
        sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.exceptionHandling(
        exceptions ->
            exceptions.authenticationEntryPoint(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
    http.authorizeHttpRequests(
        auth ->
            auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                .permitAll()
                .requestMatchers(
                    "/api/v1/health",
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/password-reset/request")
                .permitAll()
                .requestMatchers("/api/v1/admin/**", "/api/v1/users/**")
                .hasRole("ADMIN")
                .anyRequest()
                .authenticated());
    http.addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);
    http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
