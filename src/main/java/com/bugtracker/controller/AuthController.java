package com.bugtracker.controller;

import com.bugtracker.dto.LoginRequest;
import com.bugtracker.dto.RegisterRequest;
import com.bugtracker.dto.UserResponse;
import com.bugtracker.exception.UnauthorizedException;
import com.bugtracker.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Logout is handled by Spring Security at POST /api/auth/logout. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final UserService userService;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository, UserService userService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.userService = userService;
    }

    /** The frontend calls this first and sends the token in the given header on POST/PUT/PATCH/DELETE requests. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    /** Public settings the sign-in screen needs, such as whether sign-up is open. */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return Map.of("registrationEnabled", userService.isRegistrationEnabled());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest req) {
        return userService.register(req);
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest req,
                              HttpServletRequest request, HttpServletResponse response) {
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(req.email().trim(), req.password()));
        } catch (AuthenticationException e) {
            // Same message for unknown email, wrong password and disabled account.
            throw new UnauthorizedException("Invalid email or password");
        }
        request.getSession(true);
        request.changeSessionId(); // protects against session fixation
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return userService.me();
    }

    @GetMapping("/me")
    public UserResponse me() {
        return userService.me();
    }
}
