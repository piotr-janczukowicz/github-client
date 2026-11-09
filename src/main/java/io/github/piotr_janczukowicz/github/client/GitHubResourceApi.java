package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.client.pagination.GitHubListSpec;
import io.github.piotr_janczukowicz.github.pagination.GitHubPage;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpResourceApi;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.uri.PathTemplate;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Shared helpers for GitHub REST resource APIs (calls and page lists). A call is built with the verb helpers of
 * {@link HttpResourceApi} and the {@code with...} methods of {@link HttpCall}, and sent through {@link #caller}.
 *
 * <p>JSON encoding stays on {@code caller.json(call, value)}.
 *
 * <p>A resource API keeps no state besides its caller, so it is safe to share between threads when the client is.
 */
public abstract class GitHubResourceApi extends HttpResourceApi {

    /** Argument name for pagination tokens. */
    protected static final String PARAM_REQUEST = "request";

    /** Argument name for request bodies. */
    protected static final String PARAM_PAYLOAD = "payload";

    /** Argument name for list filters. */
    protected static final String PARAM_FILTER = "filter";

    private static final String PARAM_PATH = "path";

    private static final ConcurrentMap<JsonType<?>, GitHubListSpec<?>> PAGE_LIST_SPECS = new ConcurrentHashMap<>();

    /** Executes the calls of this resource. */
    protected final GitHubCaller caller;

    /**
     * Resource API that sends its calls through {@code caller}.
     *
     * @param caller executes and decodes the calls of this resource
     * @throws NullPointerException if {@code caller} is {@code null}
     */
    protected GitHubResourceApi(final GitHubCaller caller) {
        super();
        this.caller = requireNonNull(caller, "caller");
    }

    /**
     * Rejects the call before any request when the client is limited to a flavor that does not carry the operation, see
     * {@link GitHubClientConfiguration#flavor()}. Methods annotated with
     * {@link io.github.piotr_janczukowicz.github.api.Flavors} call it first.
     *
     * @param available flavors whose REST description carries the operation
     * @throws UnsupportedOperationException if a flavor is configured and is not in {@code available}
     */
    protected final void requireFlavor(final GitHubFlavor... available) {
        caller.requireFlavor(available);
    }

    /**
     * Rejects the call before any request when the client is a GitHub Enterprise Server client of a release outside
     * {@code since} to {@code until}, see {@link GitHubClientConfiguration#ghesVersion()}. Methods annotated with
     * {@link io.github.piotr_janczukowicz.github.api.GhesVersions} call it right after
     * {@link #requireFlavor(GitHubFlavor...)}.
     *
     * @param since first release that carries the operation, or empty for the oldest supported release
     * @param until last release that carries the operation, or empty for the newest supported release
     * @throws UnsupportedOperationException if a release is configured and is not in the range
     */
    protected final void requireGhesVersion(final String since, final String until) {
        caller.requireGhesVersion(since, until);
    }

    /**
     * First page of a numbered {@code page}/{@code per_page} list endpoint.
     *
     * <p>Accepts {@link io.github.piotr_janczukowicz.github.pagination.GitHubNoneRequest} and numbered
     * {@code page}/{@code per_page} tokens only.
     *
     * @param path API path template
     * @param query endpoint-specific filters
     * @param request pagination token
     * @param type list item type
     * @param values placeholder values in order
     * @param <T> item type
     * @return first HTTP page
     * @throws IOException if the transport fails
     * @throws NullPointerException if {@code path} or {@code request} is {@code null} or a value is {@code null}
     * @throws IllegalArgumentException if expansion fails or {@code request} is not a page or none variant
     */
    protected final <T> GitHubPage<T> pageList(
            final PathTemplate path,
            final Map<String, String> query,
            final GitHubPageRequest request,
            final JsonType<List<T>> type,
            final Object... values)
            throws IOException {
        return list(requireNonNull(path, PARAM_PATH), query, request, pageListSpec(type), values);
    }

    /**
     * First page of a list endpoint described by {@code spec}.
     *
     * @param path API path template
     * @param query endpoint-specific filters
     * @param request pagination token
     * @param spec decode, kinds, and {@code per_page} bind
     * @param values placeholder values in order
     * @param <T> item type
     * @return first HTTP page
     * @throws IOException if the transport fails
     * @throws NullPointerException if {@code path}, {@code request}, or {@code spec} is {@code null} or a value is
     *     {@code null}
     * @throws IllegalArgumentException if expansion fails or {@code request} is not an allowed kind
     */
    protected final <T> GitHubPage<T> list(
            final PathTemplate path,
            final Map<String, String> query,
            final GitHubPageRequest request,
            final GitHubListSpec<T> spec,
            final Object... values)
            throws IOException {
        return list(path, query, request, spec, HttpHeaders.empty(), values);
    }

    /**
     * First page of a list endpoint described by {@code spec}.
     *
     * @param path API path template
     * @param query endpoint-specific filters
     * @param request pagination token
     * @param spec decode, kinds, and {@code per_page} bind
     * @param overlayHeaders per-request headers merged onto version headers
     * @param values placeholder values in order
     * @param <T> item type
     * @return first HTTP page
     * @throws IOException if the transport fails
     * @throws NullPointerException if {@code path}, {@code request}, {@code spec}, or {@code overlayHeaders} is
     *     {@code null} or a value is {@code null}
     * @throws IllegalArgumentException if expansion fails or {@code request} is not an allowed kind
     */
    protected final <T> GitHubPage<T> list(
            final PathTemplate path,
            final Map<String, String> query,
            final GitHubPageRequest request,
            final GitHubListSpec<T> spec,
            final HttpHeaders overlayHeaders,
            final Object... values)
            throws IOException {
        requireNonNull(path, PARAM_PATH);
        requireNonNull(request, PARAM_REQUEST);
        requireNonNull(spec, "spec");
        final HttpCall call = get(path, values).withQuery(query).withHeaders(overlayHeaders);
        return caller.list(call, request, spec);
    }

    /**
     * First page of a list endpoint with a multi-valued filter query.
     *
     * @param path API path template
     * @param query endpoint-specific filters
     * @param request pagination token
     * @param spec decode, kinds, and {@code per_page} bind
     * @param values placeholder values in order
     * @param <T> item type
     * @return first HTTP page
     * @throws IOException if the transport fails
     * @throws NullPointerException if {@code path}, {@code request}, or {@code spec} is {@code null} or a value is
     *     {@code null}
     * @throws IllegalArgumentException if expansion fails or {@code request} is not an allowed kind
     */
    protected final <T> GitHubPage<T> listValues(
            final PathTemplate path,
            final Map<String, List<String>> query,
            final GitHubPageRequest request,
            final GitHubListSpec<T> spec,
            final Object... values)
            throws IOException {
        requireNonNull(path, PARAM_PATH);
        requireNonNull(request, PARAM_REQUEST);
        requireNonNull(spec, "spec");
        return caller.list(get(path, values).withQueryValues(query), request, spec);
    }

    /**
     * Sends {@code call} with {@code Accept: mediaType} and returns the body as text, decoded with the {@code charset}
     * of the response {@code Content-Type} or UTF-8 when it names none. Bytes that are not valid in that charset become
     * replacement characters. For endpoints that offer a text representation (diff, patch) next to JSON. A successful
     * response without a body (an empty diff) yields an empty string, not an empty {@link Optional}.
     *
     * @param call GET call to send
     * @param mediaType media type for the {@code Accept} header
     * @return body text, {@code ""} when a successful response has no body, or an empty {@link Optional} on HTTP 404
     *     (also when the caller has no access to a private repository)
     * @throws IOException if the transport fails
     * @throws NullPointerException if {@code call} or {@code mediaType} is {@code null}
     */
    protected final Optional<String> fetchTextOrEmpty(final HttpCall call, final String mediaType) throws IOException {
        final HttpCall accepting =
                requireNonNull(call, "call").withHeader(ACCEPT, requireNonNull(mediaType, "mediaType"));
        final HttpResponse response = caller.send(accepting, Set.of(HttpStatus.NOT_FOUND.code()));
        if (response.hasStatus(HttpStatus.NOT_FOUND)) {
            return Optional.empty();
        }
        final Charset charset = response.charset();
        return Optional.of(response.body().mapBytes(bytes -> new String(bytes, charset), () -> ""));
    }

    @SuppressWarnings("unchecked")
    private static <T> GitHubListSpec<T> pageListSpec(final JsonType<List<T>> type) {
        return (GitHubListSpec<T>) PAGE_LIST_SPECS.computeIfAbsent(type, unused -> GitHubListSpec.pages(type));
    }
}
