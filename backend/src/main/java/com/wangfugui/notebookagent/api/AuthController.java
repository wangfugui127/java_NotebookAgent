package com.wangfugui.notebookagent.api;

import com.wangfugui.notebookagent.api.dto.Dtos.LoginRequest;
import com.wangfugui.notebookagent.api.dto.Dtos.RefreshRequest;
import com.wangfugui.notebookagent.api.dto.Dtos.RegisterRequest;
import com.wangfugui.notebookagent.api.dto.Dtos.TokenResponse;
import com.wangfugui.notebookagent.core.ApiException;
import com.wangfugui.notebookagent.core.PasswordHasher;
import com.wangfugui.notebookagent.core.TokenService;
import com.wangfugui.notebookagent.domain.RefreshToken;
import com.wangfugui.notebookagent.domain.User;
import com.wangfugui.notebookagent.repository.RefreshTokenRepository;
import com.wangfugui.notebookagent.repository.UserRepository;
import com.wangfugui.notebookagent.config.AppProperties;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final TokenService tokens;
    private final AppProperties props;

    public AuthController(UserRepository users, RefreshTokenRepository refreshTokens,
                          TokenService tokens, AppProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.props = props;
    }

    @PostMapping("/register")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest body) {
        String email = body.email().toLowerCase();
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("email already registered");
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(PasswordHasher.hash(body.password()));
        user.setDisplayName(body.displayName() == null ? "" : body.displayName());
        users.save(user);
        return issue(user);
    }

    @PostMapping("/login")
    @Transactional
    public TokenResponse login(@Valid @RequestBody LoginRequest body) {
        User user = users.findByEmail(body.email().toLowerCase()).orElse(null);
        if (user == null || !user.isActive() || !PasswordHasher.verify(body.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("invalid credentials");
        }
        return issue(user);
    }

    @PostMapping("/refresh")
    @Transactional
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest body) {
        Claims claims;
        try {
            claims = tokens.decode(body.refreshToken(), "refresh");
        } catch (Exception e) {
            throw ApiException.unauthorized("invalid refresh token");
        }
        RefreshToken record = refreshTokens
                .lockActiveByFingerprint(tokens.fingerprint(body.refreshToken()))
                .orElse(null);
        if (record == null || record.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("refresh token expired or revoked");
        }
        record.setRevokedAt(Instant.now());
        refreshTokens.save(record);
        User user = users.findById(claims.getSubject())
                .orElseThrow(() -> ApiException.unauthorized("inactive user"));
        if (!user.isActive()) throw ApiException.unauthorized("inactive user");
        return issue(user);
    }

    private TokenResponse issue(User user) {
        String access = tokens.accessToken(user.getId());
        String refresh = tokens.refreshToken(user.getId());
        RefreshToken record = new RefreshToken();
        record.setUserId(user.getId());
        record.setFingerprint(tokens.fingerprint(refresh));
        record.setExpiresAt(Instant.now().plus(props.getRefreshTokenDays(), ChronoUnit.DAYS));
        refreshTokens.save(record);
        return TokenResponse.of(access, refresh);
    }
}
