package org.oagi.score.gateway.http.api.application_management.service;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.oagi.score.gateway.http.configuration.ai.ScoreMcpClientProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BrokerJwtService {

    private final RSAKey rsaKey;
    private final ECKey ecKey;
    private final String issuer;

    public BrokerJwtService(ScoreAiProperties properties,
                            ScoreMcpClientProperties mcpProperties) {
        try {
            this.rsaKey = new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
            this.ecKey = new ECKeyGenerator(Curve.P_256)
                    .keyID(UUID.randomUUID().toString())
                    .algorithm(JWSAlgorithm.ES256)
                    .generate();
            String connectionName = properties.getTools().getConnectCenterMcp().getConnectionName();
            ScoreMcpClientProperties.Connection connection = mcpProperties.connection(connectionName);
            String configuredIssuer = connection != null
                    ? connection.getAuth().getIssuerUrl() : null;
            this.issuer = StringUtils.hasText(configuredIssuer)
                    ? configuredIssuer.replaceAll("/+$", "") : "http://localhost:9000/broker";
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to generate broker JWT signing key.", e);
        }
    }

    public String issueToken(ScoreUser requester, String issuer, String audience, String algorithm, long ttlSeconds) {
        if (requester == null) {
            throw new IllegalArgumentException("Requester must not be null.");
        }

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);
        JWSAlgorithm jwsAlgorithm = jwsAlgorithm(algorithm);
        JWK signingKey = signingKey(jwsAlgorithm);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(requester.username())
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("login_id", requester.username())
                .claim("app_user_id", requester.userId().toString())
                .claim("name", requester.name())
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(jwsAlgorithm)
                        .keyID(signingKey.getKeyID())
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims);
        try {
            if (JWSAlgorithm.ES256.equals(jwsAlgorithm)) {
                jwt.sign(new ECDSASigner(ecKey));
            } else {
                jwt.sign(new RSASSASigner(rsaKey));
            }
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign broker JWT.", e);
        }
    }

    public Map<String, Object> jwks() {
        return new JWKSet(List.of(ecKey.toPublicJWK(), rsaKey.toPublicJWK())).toJSONObject();
    }

    public Map<String, Object> openIdConfiguration() {
        return Map.of(
                "issuer", issuer,
                "jwks_uri", issuer + "/.well-known/jwks.json",
                "id_token_signing_alg_values_supported", List.of("ES256", "RS256")
        );
    }

    private JWSAlgorithm jwsAlgorithm(String algorithm) {
        if (JWSAlgorithm.RS256.getName().equals(algorithm)) {
            return JWSAlgorithm.RS256;
        }
        if (algorithm == null || algorithm.isBlank() || JWSAlgorithm.ES256.getName().equals(algorithm)) {
            return JWSAlgorithm.ES256;
        }
        throw new IllegalArgumentException("Unsupported broker JWT signing algorithm: " + algorithm);
    }

    private JWK signingKey(JWSAlgorithm algorithm) {
        if (JWSAlgorithm.RS256.equals(algorithm)) {
            return rsaKey;
        }
        return ecKey;
    }
}
