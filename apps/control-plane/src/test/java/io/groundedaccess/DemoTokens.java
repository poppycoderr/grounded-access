package io.groundedaccess;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Signs test tokens with the published demo key, the same way scripts/mint-token.py does.
 */
public final class DemoTokens {

    private static final Path PRIVATE_KEY = Path.of("../../data/demo-keys/jwt-private.pem");

    private DemoTokens() {
    }

    public static String token(String subject, String tenant, String scope) {
        return sign(Map.of("sub", subject, "tenant_id", tenant, "scope", scope), "grounded-access-demo");
    }

    public static String sign(Map<String, Object> claims, String issuer) {
        return sign(claims, issuer, true);
    }

    /** A token that verifies in every respect except that it never expires, which the control plane refuses. */
    public static String signWithoutExpiry(Map<String, Object> claims) {
        return sign(claims, "grounded-access-demo", false);
    }

    private static String sign(Map<String, Object> claims, String issuer, boolean expires) {
        try {
            var builder = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(List.of("grounded-access"))
                    .issueTime(new Date());
            if (expires) {
                builder.expirationTime(Date.from(Instant.now().plusSeconds(600)));
            }
            claims.forEach(builder::claim);
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), builder.build());
            jwt.sign(new RSASSASigner(privateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("cannot sign demo token", e);
        }
    }

    private static RSAPrivateKey privateKey() throws Exception {
        String pem = Files.readString(PRIVATE_KEY).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }
}
