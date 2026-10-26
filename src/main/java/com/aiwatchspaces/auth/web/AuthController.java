package com.aiwatchspaces.auth.web;

import java.net.InetSocketAddress;

import com.aiwatchspaces.auth.AuthFacade;
import com.aiwatchspaces.auth.web.AuthDtos.LoginRequest;
import com.aiwatchspaces.auth.web.AuthDtos.LoginResponse;
import com.aiwatchspaces.auth.web.AuthDtos.RefreshRequest;
import com.aiwatchspaces.auth.web.AuthDtos.RegisterRequest;
import com.aiwatchspaces.auth.web.AuthDtos.RegisterResponse;
import com.aiwatchspaces.auth.web.AuthDtos.TokenPairResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** {@code /api/v1/auth} (SYSTEM_DESIGN §6.2). Thin: all logic is in {@link AuthFacade}. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthFacade auth;

    AuthController(AuthFacade auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Mono<RegisterResponse> register(@Valid @RequestBody RegisterRequest request, ServerHttpRequest http) {
        return auth.register(request, clientIp(http));
    }

    @PostMapping("/login")
    Mono<LoginResponse> login(@Valid @RequestBody LoginRequest request, ServerHttpRequest http) {
        return auth.login(request, clientIp(http));
    }

    @PostMapping("/refresh")
    Mono<TokenPairResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> logout(@Valid @RequestBody RefreshRequest request) {
        return auth.logout(request);
    }

    /** The socket peer address. Proxy headers are not trusted (single local deployment). */
    static String clientIp(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
