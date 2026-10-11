package com.wangfugui.notebookagent.core;

import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ATTRIBUTE = "notebookagent.user";

    private final TokenService tokenService;
    private final UserRepository users;

    public AuthInterceptor(TokenService tokenService, UserRepository users) {
        this.tokenService = tokenService;
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw ApiException.unauthorized("missing bearer token");
        }
        String token = header.substring(7).trim();
        Claims claims;
        try {
            claims = tokenService.decode(token, "access");
        } catch (Exception e) {
            throw ApiException.unauthorized("invalid token");
        }
        User user = users.findById(claims.getSubject()).orElse(null);
        if (user == null || !user.isActive()) {
            throw ApiException.unauthorized("inactive user");
        }
        request.setAttribute(USER_ATTRIBUTE, user);
        return true;
    }
}
