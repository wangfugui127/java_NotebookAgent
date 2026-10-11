package com.wangfugui.notebookagent.core;

import com.wangfugui.notebookagent.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;

@Component
public class TokenService {

    private final SecretKey key;
    private final AppProperties props;

    public TokenService(AppProperties props) {
        this.props = props;
        byte[] secret = props.getSecretKey().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(secret, 0, padded, 0, secret.length);
            secret = padded;
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String accessToken(String subject) {
        return create(subject, "access", Instant.now().plus(props.getAccessTokenMinutes(), ChronoUnit.MINUTES));
    }

    public String refreshToken(String subject) {
        return create(subject, "refresh", Instant.now().plus(props.getRefreshTokenDays(), ChronoUnit.DAYS));
    }

    private String create(String subject, String type, Instant expiry) {
        byte[] jti = new byte[16];
        new SecureRandom().nextBytes(jti);
        return Jwts.builder()
                .subject(subject)
                .claim("type", type)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiry))
                .id(HexFormat.of().formatHex(jti))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Claims decode(String token, String expectedType) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        if (!"HS256".equals(jws.getHeader().getAlgorithm())) throw new IllegalArgumentException("unexpected token algorithm");
        Claims claims = jws.getPayload();
        Object type = claims.get("type");
        if (!expectedType.equals(type)) {
            throw new IllegalArgumentException("unexpected token type");
        }
        return claims;
    }

    public String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, String> pair(String subject) {
        return Map.of("access_token", accessToken(subject), "refresh_token", refreshToken(subject));
    }
}
