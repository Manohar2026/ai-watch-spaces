package com.aiwatchspaces.auth;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.security.AuthenticatedUser;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Resolves an {@link AuthenticatedUser} controller parameter from the reactive security context
 * at the web edge (ADR-003 §5). Services receive it explicitly and never read the context.
 */
@Configuration(proxyBeanMethods = false)
class AuthenticatedUserArgumentResolver implements HandlerMethodArgumentResolver, WebFluxConfigurer {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext,
                                        ServerWebExchange exchange) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .mapNotNull(JwtAuthentications::toUser)
                .cast(Object.class)
                .switchIfEmpty(Mono.error(new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication is required.")));
    }

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(this);
    }
}
