package com.aiwatchspaces.auth;

import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Baseline reactive security chain (M1). Stateless; deny by default; health probes and info are
 * public; other actuator endpoints need global ADMIN (SYSTEM_DESIGN §6.7). 401/403 use the error
 * envelope. JWT (HS256) resource-server authentication is added in M2.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    SecurityWebFilterChain apiSecurity(ServerHttpSecurity http, ErrorResponseWriter errors) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness", "/actuator/info").permitAll()
                        .pathMatchers("/actuator/**").hasRole("ADMIN")
                        .anyExchange().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((exchange, e) ->
                                errors.write(exchange, ErrorCode.UNAUTHENTICATED, "Authentication is required."))
                        .accessDeniedHandler((exchange, e) ->
                                errors.write(exchange, ErrorCode.FORBIDDEN, "Access denied.")))
                .build();
    }
}
