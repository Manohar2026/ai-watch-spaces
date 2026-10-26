package com.aiwatchspaces.auth;

import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Reactive security chain. Stateless; deny by default. Bearer JWT (HS256) authentication via the
 * resource server; {@code ROLE_ADMIN} is granted only after a database re-check
 * ({@link JwtAuthentications}). Public: health probes, info, register, login, refresh, logout (the
 * refresh token is the logout credential). 401/403 use the error envelope; an expired token is
 * {@code 401 TOKEN_EXPIRED}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {

    /** Public auth routes: anyone may call them, and any Authorization header on them is ignored. */
    static final String[] PUBLIC_AUTH_PATHS = {"/api/v1/auth/register", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/logout"};

    @Bean
    SecurityWebFilterChain apiSecurity(ServerHttpSecurity http, ErrorResponseWriter errors, ReactiveJwtDecoder jwtDecoder,
                                       JwtAuthentications jwtAuthentications) {
        ServerAuthenticationEntryPoint entryPoint = (exchange, e) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            ErrorCode code = BearerTokenFailures.classify(e);
            return errors.write(exchange, code, code == ErrorCode.TOKEN_EXPIRED
                    ? "The access token has expired." : "Authentication is required.");
        };
        ServerWebExchangeMatcher publicAuthPaths = ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, PUBLIC_AUTH_PATHS);
        ServerBearerTokenAuthenticationConverter bearerTokens = new ServerBearerTokenAuthenticationConverter();
        ServerAuthenticationConverter bearerTokensOutsidePublicAuth = exchange -> publicAuthPaths.matches(exchange)
                .flatMap(match -> match.isMatch() ? Mono.empty() : bearerTokens.convert(exchange));
        ServerAccessDeniedHandler accessDenied = (exchange, e) ->
                errors.write(exchange, ErrorCode.FORBIDDEN, "Access denied.");
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
                        .pathMatchers(HttpMethod.POST, PUBLIC_AUTH_PATHS).permitAll()
                        .pathMatchers("/actuator/**").hasRole("ADMIN")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenConverter(bearerTokensOutsidePublicAuth)
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthentications))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .build();
    }
}
