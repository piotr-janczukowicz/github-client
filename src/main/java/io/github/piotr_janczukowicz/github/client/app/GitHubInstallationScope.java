package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNotBlank;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requirePositive;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the token request narrows the token to; empty lists and maps mean "all of the installation's". Each {@code with}
 * method checks its argument and returns a new scope, so {@link GitHubInstallationAuthConfiguration.Builder} rejects an
 * invalid scope when it is set.
 */
record GitHubInstallationScope(List<String> repositories, List<Long> repositoryIds, Map<String, String> permissions) {

    /** Most repositories one token request may name, in each of the two lists. */
    static final int MAX_REPOSITORIES = 500;

    static final GitHubInstallationScope ALL = new GitHubInstallationScope(List.of(), List.of(), Map.of());

    GitHubInstallationScope {
        repositories = List.copyOf(requireNonNull(repositories, "repositories"));
        repositoryIds = List.copyOf(requireNonNull(repositoryIds, "repositoryIds"));
        permissions = Map.copyOf(requireNonNull(permissions, "permissions"));
    }

    GitHubInstallationScope withRepositories(final Collection<String> repositoryNames) {
        requireNonNull(repositoryNames, "repositoryNames");
        if (repositoryNames.isEmpty()) {
            throw new IllegalArgumentException("repositoryNames must not be empty");
        }
        requireAtMostMaxRepositories(repositoryNames.size());
        repositoryNames.forEach(GitHubInstallationScope::requireBareRepositoryName);
        return new GitHubInstallationScope(List.copyOf(repositoryNames), repositoryIds, permissions);
    }

    GitHubInstallationScope withRepositoryIds(final Collection<Long> ids) {
        requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ids must not be empty");
        }
        requireAtMostMaxRepositories(ids.size());
        ids.forEach(id -> requirePositive(requireNonNull(id, "repository id"), "repository id"));
        return new GitHubInstallationScope(repositories, List.copyOf(ids), permissions);
    }

    GitHubInstallationScope withPermissions(final Map<String, String> permissionLevels) {
        requireNonNull(permissionLevels, "permissionLevels");
        if (permissionLevels.isEmpty()) {
            throw new IllegalArgumentException("permissionLevels must not be empty");
        }
        permissionLevels.forEach((name, level) -> {
            requireNotBlank(name, "permission name");
            requireNotBlank(level, "permission level");
        });
        return new GitHubInstallationScope(repositories, repositoryIds, Map.copyOf(permissionLevels));
    }

    Map<String, Object> toRequestBody() {
        final Map<String, Object> body = new LinkedHashMap<>();
        if (!repositories.isEmpty()) {
            body.put("repositories", repositories);
        }
        if (!repositoryIds.isEmpty()) {
            body.put("repository_ids", repositoryIds);
        }
        if (!permissions.isEmpty()) {
            body.put("permissions", new TreeMap<>(permissions));
        }
        return Collections.unmodifiableMap(body);
    }

    private static void requireAtMostMaxRepositories(final int count) {
        if (count > MAX_REPOSITORIES) {
            throw new IllegalArgumentException("GitHub allows at most " + MAX_REPOSITORIES + " repositories");
        }
    }

    private static void requireBareRepositoryName(final String name) {
        requireNotBlank(name, "repository name");
        if (name.contains("/")) {
            throw new IllegalArgumentException("repository name must not contain '/', give the name without the owner");
        }
    }
}
