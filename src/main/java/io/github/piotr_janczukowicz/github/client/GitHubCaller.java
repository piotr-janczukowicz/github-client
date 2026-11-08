package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKindValidators.requireAllowedPageRequestKind;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.client.pagination.GitHubListSpec;
import io.github.piotr_janczukowicz.github.client.pagination.GitHubPageQueries;
import io.github.piotr_janczukowicz.github.client.pagination.GitHubPageTarget;
import io.github.piotr_janczukowicz.github.client.pagination.GitHubPagers;
import io.github.piotr_janczukowicz.github.client.pagination.GitHubSearchPageDecoder;
import io.github.piotr_janczukowicz.github.json.GitHubJsonCodecs;
import io.github.piotr_janczukowicz.github.pagination.GitHubPage;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKind;
import io.github.piotr_janczukowicz.github.pagination.GitHubSearchEnvelope;
import io.github.piotr_janczukowicz.http.HttpHeaderNames;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.client.AbstractHttpCaller;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Executes {@link HttpCall}s against the GitHub REST API. The generic operations, such as {@code fetch},
 * {@code fetchUnless} and {@code fetchWhenReady}, are on {@link AbstractHttpCaller}; this class adds what needs
 * GitHub's pagination: binding page parameters to a call and starting a page walk.
 *
 * <p>A caller holds only its client and codec, both fixed at construction, so it is safe to share between threads when
 * the client is. A page walk it starts is not: see {@link io.github.piotr_janczukowicz.github.pagination.GitHubPage}.
 */
public final class GitHubCaller extends AbstractHttpCaller {

    private final GitHubClient client;
    private final JsonCodec jsonCodec;

    /**
     * Caller over {@code client} with {@code jsonCodec}; unit tests use it to inject a codec.
     *
     * @param client sends the calls
     * @param jsonCodec encodes request bodies and decodes responses
     * @throws NullPointerException if an argument is {@code null}
     */
    GitHubCaller(final GitHubClient client, final JsonCodec jsonCodec) {
        super(client, jsonCodec);
        this.client = client;
        this.jsonCodec = jsonCodec;
    }

    /**
     * Caller over {@code client} with the default JSON codec of this library.
     *
     * @param client sends the calls
     * @return caller
     * @throws NullPointerException if {@code client} is {@code null}
     */
    public static GitHubCaller of(final GitHubClient client) {
        return new GitHubCaller(client, GitHubJsonCodecs.defaults());
    }

    /**
     * Caller over the management console view of this caller's client, see {@link GitHubClient#management()}. The calls
     * of {@link #managementCall} are sent through it.
     *
     * @return caller of the console
     * @throws UnsupportedOperationException if the client is not a GitHub Enterprise Server client
     */
    public GitHubCaller management() {
        return new GitHubCaller(client.management(), jsonCodec);
    }

    /**
     * A call to the GitHub Enterprise Server management console. Build it with, and send it through, the caller that
     * {@link #management()} returns: a caller of the API client sends it to the API origin guard, which refuses the
     * console. The path is below {@code /manage/v1/} and outside the API base path; the call is authenticated with
     * {@code credentials} instead of the credentials of the client.
     *
     * @param method HTTP method
     * @param path absolute path, starting with {@code /manage/v1/}
     * @param credentials management console credentials
     * @return call to the console
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code path} is not below {@code /manage/v1/}
     */
    public HttpCall managementCall(
            final HttpMethod method, final String path, final GitHubManagementCredentials credentials) {
        requireNonNull(method, "method");
        requireNonNull(path, "path");
        requireNonNull(credentials, "credentials");
        if (!path.startsWith(GitHubApiBase.MANAGE_PATH) || path.length() == GitHubApiBase.MANAGE_PATH.length()) {
            throw new IllegalArgumentException("path must be below " + GitHubApiBase.MANAGE_PATH + ", but is: " + path);
        }
        if (isAmbiguousPath(path)) {
            throw new IllegalArgumentException("path must be below " + GitHubApiBase.MANAGE_PATH
                    + " without dot or empty segments, query, fragment, backslash or encoded separator, but is: "
                    + path);
        }
        return HttpCall.of(method, client.baseUri().resolve(path))
                .withHeader(
                        HttpHeaderNames.AUTHORIZATION,
                        credentials.authentication().headerValue());
    }

    /**
     * {@code call} with the pagination query parameters of {@code request}, after checking {@code kinds}.
     *
     * @param call call to extend
     * @param request pagination token
     * @param kinds allowed active kinds
     * @return new call
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code request} is not an allowed kind
     */
    public HttpCall pageParameters(
            final HttpCall call, final GitHubPageRequest request, final Set<GitHubPageRequestKind> kinds) {
        requireNonNull(call, "call");
        requireAllowedPageRequestKind(request, kinds);
        final PaginationConfiguration pagination = client.pagination();
        return call.withQueryValues(GitHubPageQueries.queryValues(
                request, call.query(), GitHubPageQueries.boundPerPage(request, pagination), true));
    }

    /**
     * First page of a list endpoint described by {@code spec}.
     *
     * @param call call to the list endpoint
     * @param request pagination token
     * @param spec decode, kinds, and {@code per_page} bind
     * @param <T> item type
     * @return first HTTP page
     * @throws IOException if the transport fails
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code call} has no API path (checked first), is not a GET or has a body;
     *     otherwise if {@code request} kind is not allowed (enforced by the pager)
     */
    public <T> GitHubPage<T> list(final HttpCall call, final GitHubPageRequest request, final GitHubListSpec<T> spec)
            throws IOException {
        requireNonNull(request, GitHubResourceApi.PARAM_REQUEST);
        requireNonNull(spec, "spec");
        return GitHubPagers.start(client, client.pagination(), targetOf(call), request, spec);
    }

    /**
     * First page of a numbered {@code page}/{@code per_page} search endpoint. Each response body is decoded as
     * {@code type} with the codec of this caller, then mapped to the page envelope.
     *
     * @param call call to the search endpoint
     * @param request pagination token
     * @param type response body type, including type arguments
     * @param envelope maps the decoded body to the page envelope
     * @param <R> response body type
     * @param <T> item type
     * @return first HTTP page
     * @throws IOException if the transport fails
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code call} has no API path (checked first), is not a GET or has a body;
     *     otherwise if {@code request} kind is not allowed (enforced by the pager)
     */
    public <R, T> GitHubPage<T> search(
            final HttpCall call,
            final GitHubPageRequest request,
            final JsonType<R> type,
            final Function<R, GitHubSearchEnvelope<T>> envelope)
            throws IOException {
        requireNonNull(request, GitHubResourceApi.PARAM_REQUEST);
        requireNonNull(type, "type");
        requireNonNull(envelope, "envelope");
        final GitHubSearchPageDecoder<T> decoder = response -> envelope.apply(decode(response, type));
        return GitHubPagers.startSearch(client, client.pagination(), targetOf(call), request, decoder);
    }

    /**
     * Whether {@code path} could be read differently by a client, a proxy and the server: it contains a query, a
     * fragment, a backslash, an empty segment, a dot segment (also percent-encoded) or an encoded path separator.
     *
     * @param path absolute path to check
     * @return {@code true} if the path is ambiguous and must be refused
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public static boolean isAmbiguousPath(final String path) {
        final String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("?")
                || lower.contains("#")
                || lower.contains("\\")
                || lower.contains("//")
                || lower.contains("%2f")
                || lower.contains("%5c")
                || Arrays.stream(lower.split("/"))
                        .map(segment -> segment.replace("%2e", "."))
                        .anyMatch(segment -> ".".equals(segment) || "..".equals(segment));
    }

    /**
     * Fails fast when the flavor configured for this client (see {@link GitHubClientConfiguration#flavor()}) is not one
     * of {@code available}. A client without a configured flavor allows every operation.
     *
     * @param available flavors whose REST description carries the operation
     * @throws UnsupportedOperationException if a flavor is configured and is not in {@code available}
     */
    void requireFlavor(final GitHubFlavor... available) {
        final Optional<GitHubFlavor> configured = client.flavor();
        if (configured.isPresent() && !Arrays.asList(available).contains(configured.get())) {
            throw new UnsupportedOperationException("This operation is not available in the " + configured.get()
                    + " flavor of the GitHub REST API; it exists in: " + Arrays.toString(available));
        }
    }

    /**
     * Fails fast when the GitHub Enterprise Server release configured for this client (see
     * {@link GitHubClientConfiguration#ghesVersion()}) is outside {@code since} to {@code until}. A client that is not
     * a {@link GitHubFlavor#GHES} client allows every operation here; {@link #requireFlavor(GitHubFlavor...)} decides
     * whether its flavor carries the operation.
     *
     * @param since first release that carries the operation, written {@code MAJOR.MINOR}, or empty for the oldest
     *     supported release
     * @param until last release that carries the operation, written {@code MAJOR.MINOR}, or empty for the newest
     *     supported release
     * @throws UnsupportedOperationException if a release is configured and is not in the range
     * @throws IllegalArgumentException if {@code since} or {@code until} is neither empty nor {@code MAJOR.MINOR}
     */
    void requireGhesVersion(final String since, final String until) {
        final Optional<GhesVersion> configured = client.ghesVersion();
        if (configured.isEmpty()) {
            return;
        }
        final GhesVersion version = configured.get();
        final boolean isBefore = !since.isEmpty() && version.compareTo(GhesVersion.parse(since)) < 0;
        final boolean isAfter = !until.isEmpty() && version.compareTo(GhesVersion.parse(until)) > 0;
        if (isBefore || isAfter) {
            throw new UnsupportedOperationException("This operation is not available in GitHub Enterprise Server "
                    + version + "; it exists " + rangeOf(since, until));
        }
    }

    private static String rangeOf(final String since, final String until) {
        if (since.isEmpty()) {
            return "up to " + until;
        }
        return until.isEmpty() ? "from " + since : "from " + since + " up to " + until;
    }

    // The pager sends a plain GET of the path, query and headers, so a call that asks for anything else is refused
    // instead of being sent as a different request.
    private static GitHubPageTarget targetOf(final HttpCall call) {
        final String path = call.requirePath();
        if (call.method() != HttpMethod.GET) {
            throw new IllegalArgumentException("a list or search call must be a GET, not " + call.method());
        }
        if (!call.body().isAbsent()) {
            throw new IllegalArgumentException("a list or search call must not have a body");
        }
        return new GitHubPageTarget(path, call.query(), call.headers());
    }
}
