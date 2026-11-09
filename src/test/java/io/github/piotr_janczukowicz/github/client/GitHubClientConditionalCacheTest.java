package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_LIMIT;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_USED;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.DATE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ETAG;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.IF_NONE_MATCH;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LINK;
import static io.github.piotr_janczukowicz.test.TestClients.perPageQuery;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.GitHubHeaders;
import io.github.piotr_janczukowicz.github.client.exception.GitHubRateLimitException;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitSnapshot;
import io.github.piotr_janczukowicz.github.pagination.GitHubPage;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProviders;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.json.JsonTypes;
import io.github.piotr_janczukowicz.test.SwitchableAuthenticationProvider;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.uri.PathTemplate;
import io.github.piotr_janczukowicz.util.DateTimeUtils;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@UnitTest
class GitHubClientConditionalCacheTest {

    private static final String ETAG_VALUE = "W/\"keys-v1\"";
    private static final String KEYS_PAGE_ONE = "[{\"key\":\"ssh-ed25519 AAAA1\",\"id\":1,"
            + "\"url\":\"https://api.github.com/user/keys/1\",\"title\":\"one\","
            + "\"created_at\":\"2020-06-11T21:31:57Z\",\"verified\":true,\"read_only\":false}]";
    private static final String KEYS_PAGE_TWO = "[{\"key\":\"ssh-ed25519 AAAA2\",\"id\":2,"
            + "\"url\":\"https://api.github.com/user/keys/2\",\"title\":\"two\","
            + "\"created_at\":\"2020-06-11T21:31:57Z\",\"verified\":true,\"read_only\":false}]";
    private static final String NEXT_PAGE_LINK =
            "<https://api.github.com/user/keys?page=2&" + perPageQuery() + ">; rel=\"next\"";
    private static final HttpCall USER = HttpCall.get("/user");
    private static final HttpCall META = HttpCall.get("/meta");

    record TestKey(long id) {}

    private static final class KeysApi extends GitHubResourceApi {

        private static final PathTemplate USER_KEYS = PathTemplate.of("/user/keys");
        private static final JsonType<List<TestKey>> KEYS_TYPE = JsonTypes.listOf(TestKey.class);

        KeysApi(final GitHubCaller caller) {
            super(caller);
        }

        GitHubPage<TestKey> listPublicKeys() throws IOException {
            return pageList(USER_KEYS, Map.of(), GitHubPageRequest.byPage(), KEYS_TYPE);
        }
    }

    @Test
    void execute_shouldRecordRateLimit_whenNotModifiedCarriesRemaining() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(rateLimited(HttpStatus.OK.code(), "{}", "4999", ETAG, ETAG_VALUE))
                .thenReturn(rateLimited(HttpStatus.NOT_MODIFIED.code(), null, "4998", ETAG, ETAG_VALUE));
        final GitHubClient client = TestGitHubClients.defaultStack(transport);
        final HttpResponse response;
        final GitHubRateLimitSnapshot core;
        try (client) {
            client.execute(USER);
            response = client.execute(USER);
            core = client.lastRateLimit("core").orElseThrow();
        }

        assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.code());
            softly.assertThat(core.remaining()).isEqualTo(4998);
        });
    }

    @Test
    void execute_shouldFailFastOnNextCall_whenNotModifiedUsesUpQuota() throws Exception {
        final Instant reset = Instant.EPOCH.plusSeconds(600);
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(rateLimited(HttpStatus.OK.code(), "{}", "4999", ETAG, ETAG_VALUE))
                .thenReturn(exhausted(reset));
        final GitHubClient client = TestGitHubClients.defaultStack(transport);
        final Throwable thrown;
        try (client) {
            client.execute(USER);
            client.execute(USER);
            thrown = catchThrowable(() -> client.execute(USER));
        }

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(GitHubRateLimitException.class);
            softly.assertThat(((GitHubRateLimitException) thrown).isClientSideBlock())
                    .isTrue();
        });
        verify(transport, times(2)).execute(any());
    }

    @Test
    void listPublicKeys_shouldFollowLinkOfCachedPage_whenRepeatedAndNotModified() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(page(HttpStatus.OK.code(), KEYS_PAGE_ONE, ETAG, ETAG_VALUE, LINK, NEXT_PAGE_LINK))
                .thenReturn(page(HttpStatus.NOT_MODIFIED.code(), null, ETAG, ETAG_VALUE))
                .thenReturn(page(HttpStatus.OK.code(), KEYS_PAGE_TWO));
        final GitHubClient client = TestGitHubClients.defaultStack(transport);
        final KeysApi keys = new KeysApi(TestClients.caller(client));
        final GitHubPage<TestKey> repeated;
        final Optional<GitHubPage<TestKey>> next;
        try (client) {
            keys.listPublicKeys();
            repeated = keys.listPublicKeys();
            next = repeated.next();
        }
        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(3)).execute(sent.capture());

        assertSoftly(softly -> {
            softly.assertThat(repeated.items()).extracting(TestKey::id).containsExactly(1L);
            softly.assertThat(next).isPresent();
            softly.assertThat(next.orElseThrow().items())
                    .extracting(TestKey::id)
                    .containsExactly(2L);
            softly.assertThat(sent.getAllValues().get(1).headers().get(IF_NONE_MATCH))
                    .contains(ETAG_VALUE);
            softly.assertThat(sent.getAllValues().get(2).uri().toString()).contains("page=2");
        });
    }

    @Test
    void execute_shouldDropOldestEntry_whenConditionalCacheMaxEntriesIsOne() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(page(HttpStatus.OK.code(), "{}", ETAG, ETAG_VALUE))
                .thenReturn(page(HttpStatus.OK.code(), "{}", ETAG, ETAG_VALUE))
                .thenReturn(page(HttpStatus.OK.code(), "{}"));
        final GitHubClientConfiguration configuration = GitHubClientConfiguration.builder()
                .conditionalCacheMaxEntries(1)
                .build();
        final GitHubClient client = TestGitHubClients.defaultStack(transport, configuration);
        try (client) {
            client.execute(USER);
            client.execute(META);
            client.execute(USER);
        }
        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(3)).execute(sent.capture());

        assertThat(sent.getAllValues().get(2).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotKeepBody_whenConditionalCacheMaxBodyBytesIsSmall() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(page(HttpStatus.OK.code(), "{\"a\":1}", ETAG, ETAG_VALUE))
                .thenReturn(page(HttpStatus.OK.code(), "{\"a\":1}"));
        final GitHubClientConfiguration configuration = GitHubClientConfiguration.builder()
                .conditionalCacheMaxBodyBytes(4)
                .build();
        final GitHubClient client = TestGitHubClients.defaultStack(transport, configuration);
        try (client) {
            client.execute(USER);
            client.execute(USER);
        }
        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(sent.capture());

        assertThat(sent.getAllValues().get(1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotServeAnonymousEntryToToken_whenSameUrlCached() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(page(HttpStatus.OK.code(), "{}", ETAG, ETAG_VALUE))
                .thenReturn(page(HttpStatus.OK.code(), "{}"));
        final SwitchableAuthenticationProvider credential =
                new SwitchableAuthenticationProvider(AuthenticationProviders.none());
        final GitHubClient client = TestGitHubClients.defaultStack(transport, credential);
        try (client) {
            client.execute(USER);
            credential.use(TestClients.auth());
            client.execute(USER);
        }
        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(sent.capture());

        assertThat(sent.getAllValues().get(1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotServeTokenEntryToAnonymous_whenSameUrlCached() throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(page(HttpStatus.OK.code(), "{}", ETAG, ETAG_VALUE))
                .thenReturn(page(HttpStatus.OK.code(), "{}"));
        final SwitchableAuthenticationProvider credential = new SwitchableAuthenticationProvider(TestClients.auth());
        final GitHubClient client = TestGitHubClients.defaultStack(transport, credential);
        try (client) {
            client.execute(USER);
            credential.use(AuthenticationProviders.none());
            client.execute(USER);
        }
        final ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(sent.capture());

        assertThat(sent.getAllValues().get(1).headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotReuseEntry_whenApiVersionDiffers() throws Exception {
        final HttpRequest second = secondSentRequest(
                HttpHeaders.of(GitHubHeaders.API_VERSION, "2026-03-10"),
                HttpHeaders.of(GitHubHeaders.API_VERSION, "2022-11-28"));

        assertThat(second.headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldNotReuseEntry_whenAcceptDiffers() throws Exception {
        final HttpRequest second = secondSentRequest(
                HttpHeaders.of(ACCEPT, "application/vnd.github.raw"),
                HttpHeaders.of(ACCEPT, "application/vnd.github+json"));

        assertThat(second.headers().get(IF_NONE_MATCH)).isEmpty();
    }

    @Test
    void execute_shouldSendValidator_whenVaryingHeadersRepeat() throws Exception {
        final HttpRequest second = secondSentRequest(
                HttpHeaders.of(GitHubHeaders.API_VERSION, "2026-03-10", ACCEPT, "application/vnd.github+json"),
                HttpHeaders.of(GitHubHeaders.API_VERSION, "2026-03-10", ACCEPT, "application/vnd.github+json"));

        assertThat(second.headers().get(IF_NONE_MATCH)).contains(ETAG_VALUE);
    }

    private static HttpRequest secondSentRequest(final HttpHeaders first, final HttpHeaders second) throws Exception {
        final HttpTransport transport = HttpTransportMocks.mock();
        when(transport.execute(any()))
                .thenReturn(rateLimited(HttpStatus.OK.code(), "{}", "4999", ETAG, ETAG_VALUE))
                .thenReturn(rateLimited(HttpStatus.OK.code(), "{}", "4998", ETAG, ETAG_VALUE));
        final GitHubClient client = TestGitHubClients.defaultStack(transport);
        try (client) {
            client.execute(HttpCall.get("/meta").withHeaders(first));
            client.execute(HttpCall.get("/meta").withHeaders(second));
        }
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        return captor.getAllValues().get(1);
    }

    private static HttpResponse rateLimited(
            final int status, final String body, final String remaining, final String... namesAndValues) {
        final List<String> headers = new ArrayList<>(List.of(namesAndValues));
        headers.addAll(List.of(
                RATE_LIMIT_LIMIT, "5000",
                RATE_LIMIT_REMAINING, remaining,
                RATE_LIMIT_USED, "1",
                RATE_LIMIT_RESET, "1893456000",
                RATE_LIMIT_RESOURCE, "core"));
        return response(status, body, headers.toArray(String[]::new));
    }

    private static HttpResponse exhausted(final Instant reset) {
        return response(
                HttpStatus.NOT_MODIFIED.code(),
                null,
                DATE,
                DateTimeUtils.toRfc1123(Instant.EPOCH),
                ETAG,
                ETAG_VALUE,
                RATE_LIMIT_LIMIT,
                "5000",
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_USED,
                "5000",
                RATE_LIMIT_RESET,
                RateLimitResponses.epoch(reset),
                RATE_LIMIT_RESOURCE,
                "core");
    }

    private static HttpResponse page(final int status, final String body, final String... namesAndValues) {
        return response(status, body, namesAndValues);
    }
}
