package com.rescuemesh.server.auth;

import com.rescuemesh.server.domain.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues HS256 JWTs for a {@link User}. Claims: {@code sub} = username,
 * {@code role} = role name (mapped to a {@code ROLE_*} authority by the
 * resource-server converter), {@code deviceId} = bound device, when present.
 */
@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final long expirationHours;

    public JwtService(JwtEncoder jwtEncoder,
                      @Value("${rescuemesh.jwt.expiration-hours}") long expirationHours) {
        this.jwtEncoder = jwtEncoder;
        this.expirationHours = expirationHours;
    }

    public String issueToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("rescuemesh-server")
                .issuedAt(now)
                .expiresAt(now.plus(expirationHours, ChronoUnit.HOURS))
                .subject(user.getUsername())
                .claim("role", user.getRole().name());
        if (user.getDeviceId() != null) {
            claims.claim("deviceId", user.getDeviceId());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    public long getExpirationHours() {
        return expirationHours;
    }
}
