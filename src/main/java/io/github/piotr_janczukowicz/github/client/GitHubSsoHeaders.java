package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the {@code X-GitHub-SSO} response header, which GitHub sends when a token is not authorized for the SAML single
 * sign-on of an organization. On a 403 the header is {@code required; url=...}, and the link is in
 * {@code GitHubForbiddenException#ssoUrl()}. On a list response it is {@code partial-results; organizations=1,2,...}:
 * GitHub left out items of those organizations, and {@link #partialResultOrganizations} lists their ids.
 */
public final class GitHubSsoHeaders {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubSsoHeaders.class);

    private static final String PARTIAL_RESULTS = "partial-results";
    private static final String ORGANIZATIONS = "organizations";
    private static final String URL_MARKER = "url=";
    private static final Pattern ORGANIZATION_ID = Pattern.compile("\\d{1,19}");
    private static final String HEADERS_PARAMETER = "headers";

    private GitHubSsoHeaders() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Ids of the organizations whose items GitHub left out of a list response, from a {@code partial-results} header.
     * The header name and the parameter name are matched in any letter case, and whitespace around the values is
     * ignored. An id that is not a positive decimal number of up to 19 digits, or that overflows a {@code long}, is
     * ignored.
     *
     * @param headers response headers of a list response
     * @return organization ids in the order given; empty when the header is absent or does not say
     *     {@code partial-results}
     * @throws NullPointerException if {@code headers} is {@code null}
     */
    public static List<Long> partialResultOrganizations(final HttpHeaders headers) {
        requireNonNull(headers, HEADERS_PARAMETER);
        return headers.get(GitHubHeaders.SSO)
                .map(GitHubSsoHeaders::organizationIds)
                .orElse(List.of());
    }

    /**
     * Link to authorize the token for SAML single sign-on, from the {@code url=} part of the header.
     *
     * @param headers response headers
     * @return the link, or empty when the header is absent or has no {@code url=}
     * @throws NullPointerException if {@code headers} is {@code null}
     */
    static Optional<String> authorizationUrl(final HttpHeaders headers) {
        requireNonNull(headers, HEADERS_PARAMETER);
        return headers.get(GitHubHeaders.SSO).flatMap(value -> Optional.ofNullable(urlOf(value)));
    }

    /**
     * Logs once, at DEBUG, that GitHub returned partial results, with the number of organizations it named. Nothing
     * else is logged: the link and the token are never part of it. Called once for each response that reaches the
     * client's response-headers observer.
     *
     * @param headers response headers
     * @throws NullPointerException if {@code headers} is {@code null}
     */
    static void logPartialResults(final HttpHeaders headers) {
        requireNonNull(headers, HEADERS_PARAMETER);
        headers.get(GitHubHeaders.SSO)
                .filter(GitHubSsoHeaders::isPartialResults)
                .ifPresent(header -> LOG.atDebug()
                        .setMessage(
                                "GitHub returned partial results: items of {} SSO-protected organization(s) are left out")
                        .addArgument(() -> organizationIds(header).size())
                        .log());
    }

    private static List<Long> organizationIds(final String header) {
        if (!isPartialResults(header)) {
            return List.of();
        }
        for (final String parameter : header.split(";")) {
            final int equals = parameter.indexOf('=');
            if (equals >= 0
                    && ORGANIZATIONS.equalsIgnoreCase(
                            parameter.substring(0, equals).trim())) {
                return organizationIdList(parameter.substring(equals + 1));
            }
        }
        return List.of();
    }

    private static List<Long> organizationIdList(final String value) {
        final List<Long> ids = new ArrayList<>();
        for (final String item : value.split(",")) {
            organizationId(item.trim()).ifPresent(ids::add);
        }
        return List.copyOf(ids);
    }

    private static Optional<Long> organizationId(final String text) {
        if (!ORGANIZATION_ID.matcher(text).matches()) {
            return Optional.empty();
        }
        try {
            final long id = Long.parseLong(text);
            return id > 0 ? Optional.of(id) : Optional.empty();
        } catch (final NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    private static boolean isPartialResults(final String header) {
        return PARTIAL_RESULTS.equalsIgnoreCase(header.split(";", 2)[0].trim());
    }

    private static @Nullable String urlOf(final String header) {
        final int index = header.toLowerCase(Locale.ROOT).indexOf(URL_MARKER);
        if (index < 0) {
            return null;
        }
        final String rest = header.substring(index + URL_MARKER.length()).trim();
        final int end = rest.indexOf(';');
        return end < 0 ? rest : rest.substring(0, end).trim();
    }
}
