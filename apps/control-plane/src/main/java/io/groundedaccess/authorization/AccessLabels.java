package io.groundedaccess.authorization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who may read a document version. An empty department or project set means that attribute does not restrict access. Departments match when the
 * principal's department is one of them; projects match when the principal has at least one of them.
 */
public record AccessLabels(
        Classification classification,

        Set<String> allowedDepartments,

        Set<String> requiredProjects) {

    public static final AccessLabels UNRESTRICTED = new AccessLabels(Classification.PUBLIC, Set.of(), Set.of());

    public AccessLabels {
        allowedDepartments = Set.copyOf(allowedDepartments);
        requiredProjects = Set.copyOf(requiredProjects);
    }

    /**
     * Identifies the labels independently of the order they were written in, so a submission with the same labels is recognised as unchanged.
     */
    public String sha256() {
        String canonical = classification.column() + "\n" + String.join("\u001F", new TreeSet<>(allowedDepartments)) + "\n"
                + String.join("\u001F", new TreeSet<>(requiredProjects));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
