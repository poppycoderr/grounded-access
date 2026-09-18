package io.groundedaccess.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Token verification settings. The defaults trust the public demo key, which is insecure by design (see data/demo-keys/README.md).
 */
@ConfigurationProperties("ga.identity")
public record IdentityProperties(
        String issuer,

        String audience,

        Resource publicKey) {
}
