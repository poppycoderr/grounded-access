package io.groundedaccess.authorization;

import java.util.Set;

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
}
