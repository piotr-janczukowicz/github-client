package io.github.piotr_janczukowicz.github.client.pagination;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.API_VERSION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.ACCEPT;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.AUTHORIZATION;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LINK;
import static io.github.piotr_janczukowicz.http.HttpMethod.GET;
import static io.github.piotr_janczukowicz.test.support.GitHubClientWireMockSupport.CODEC;
import static io.github.piotr_janczukowicz.test.support.GitHubClientWireMockSupport.client;
import static io.github.piotr_janczukowicz.test.support.GitHubClientWireMockSupport.jsonBytes;
import static io.github.piotr_janczukowicz.test.support.GitHubClientWireMockSupport.linkNext;
import static io.github.piotr_janczukowicz.test.support.GitHubClientWireMockSupport.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.pagination.GitHubPage;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageNumberRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKind;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKindValidators;
import io.github.piotr_janczukowicz.github.pagination.GitHubPaginationException;
import io.github.piotr_janczukowicz.github.pagination.GitHubPaginationLimitExceededException;
import io.github.piotr_janczukowicz.github.pagination.GitHubSinceRequest;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.json.JsonTypes;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.test.model.TestUser;
import io.github.piotr_janczukowicz.test.support.GitHubClientWireMockSupport;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@IntegrationTest
class GitHubPagerWalkIntegrationTest {

    private static final JsonType<List<TestUser>> USERS_TYPE = JsonTypes.listOf(TestUser.class);
    private static final String ITEMS_PATH = "/items";

    @RegisterExtension
    static final StubHttpServer HTTP = new StubHttpServer();

    @Test
    void loadNext_shouldFollowAbsoluteLink_whenPageRequestAndNextPresent() throws Exception {
        HTTP.stub(
                GET,
                ITEMS_PATH,
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-one.json"))
                        .header(LINK, linkNext(HTTP.uri("/items?page=2&" + TestClients.perPageQuery()))),
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-two.json")));

        assertWalkEndsAfterSecondPage(GitHubPageRequest.byPage());
    }

    @Test
    void loadNext_shouldReturnEmpty_whenLinkPresentWithoutNext() throws Exception {
        HTTP.stub(
                GET,
                ITEMS_PATH,
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-one-two.json"))
                        .header(LINK, "<https://example.test/items?page=1&per_page=2>; rel=\"prev\""));

        final GitHubPage<TestUser> first;
        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClientWireMockSupport.WireMockClient wire = client(HTTP)) {
            first = load(wire.client(), GitHubPageNumberRequest.of(1, 2));
            second = first.next();
        }

        assertThat(second).isEmpty();
        HTTP.verifyRequested(GET, ITEMS_PATH, 1);
    }

    @Test
    void loadNext_shouldFollowRelativeLink_whenPageRequestAndNextPresent() throws Exception {
        HTTP.stub(
                GET,
                ITEMS_PATH,
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-one.json"))
                        .header(LINK, "</items?page=2&" + TestClients.perPageQuery() + ">; rel=\"next\""),
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-two.json")));

        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClientWireMockSupport.WireMockClient wire = client(HTTP)) {
            second = load(wire.client(), GitHubPageRequest.byPage()).next();
        }

        assertSoftly(softly -> {
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items())
                    .singleElement()
                    .extracting(TestUser::login)
                    .isEqualTo("two");
        });
        HTTP.verifyRequested(GET, ITEMS_PATH, 2);
    }

    @Test
    void loadNext_shouldReturnEmpty_whenFullPageWithoutLinkHeader() throws Exception {
        HTTP.stub(GET, ITEMS_PATH, okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-one.json")));

        final GitHubPage<TestUser> first;
        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClientWireMockSupport.WireMockClient wire = client(HTTP)) {
            first = load(wire.client(), GitHubPageNumberRequest.of(1, 1));
            second = first.next();
        }

        assertThat(second).isEmpty();
        HTTP.verifyRequested(GET, ITEMS_PATH, 1);
    }

    @Test
    void loadNext_shouldFollowLink_whenCursorRequestAndAfterPresent() throws Exception {
        HTTP.stub(
                GET,
                ITEMS_PATH,
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-one.json"))
                        .header(
                                LINK,
                                linkNext(HTTP.uri("/items?after=cursor-after-token&" + TestClients.perPageQuery()))),
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-two.json")));

        assertWalkEndsAfterSecondPage(GitHubPageRequest.byCursor());
    }

    @Test
    void all_shouldReturnAllPages_whenMultiplePagesExist() throws Exception {
        HTTP.stub(
                GET,
                ITEMS_PATH,
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-one.json"))
                        .header(LINK, linkNext(HTTP.uri("/items?page=2&" + TestClients.perPageQuery()))),
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-two.json")));

        final List<TestUser> all;
        try (final GitHubClientWireMockSupport.WireMockClient wire = client(HTTP)) {
            all = load(wire.client(), GitHubPageRequest.byPage()).all();
        }

        assertSoftly(softly -> {
            softly.assertThat(all).hasSize(2);
            softly.assertThat(all).extracting(TestUser::login).containsExactly("one", "two");
        });
        HTTP.verifyRequested(GET, ITEMS_PATH, 2);
    }

    @Test
    void forEachPage_shouldInvokeConsumerForEachPage_whenMultiplePagesExist() throws Exception {
        HTTP.stub(
                GET,
                ITEMS_PATH,
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-one.json"))
                        .header(LINK, linkNext(HTTP.uri("/items?page=2&" + TestClients.perPageQuery()))),
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-two.json")));

        final AtomicInteger pageCount = new AtomicInteger();
        try (final GitHubClientWireMockSupport.WireMockClient wire = client(HTTP)) {
            load(wire.client(), GitHubPageRequest.byPage()).forEachPage(unused -> pageCount.incrementAndGet());
        }

        assertThat(pageCount).hasValue(2);
        HTTP.verifyRequested(GET, ITEMS_PATH, 2);
    }

    @Test
    void loadNext_shouldThrow_whenMaxPagesReachedWithNextLink() throws Exception {
        HTTP.stub(
                GET,
                ITEMS_PATH,
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-one.json"))
                        .header(LINK, linkNext(HTTP.uri("/items?page=2&" + TestClients.perPageQuery()))));

        final Throwable thrown;
        try (final GitHubClientWireMockSupport.WireMockClient wire = client(
                HTTP, PaginationConfiguration.builder().maxPages(1).perPage(30).build())) {
            final GitHubPage<TestUser> first = load(wire.client(), GitHubPageRequest.byPage());
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown).isInstanceOf(GitHubPaginationLimitExceededException.class);
        HTTP.verifyRequested(GET, ITEMS_PATH, 1);
    }

    @Test
    void loadNext_shouldThrowPaginationException_whenNextHostDiffers() throws Exception {
        HTTP.stub(
                GET,
                ITEMS_PATH,
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-one.json"))
                        .header(LINK, linkNext(URI.create("https://evil.example/items?page=2"))));

        final GitHubPage<TestUser> first;
        try (final GitHubClientWireMockSupport.WireMockClient wire = client(HTTP)) {
            first = load(wire.client(), GitHubSinceRequest.first(1));
        }

        final Throwable thrown = catchThrowable(first::next);

        assertThat(thrown).isInstanceOf(GitHubPaginationException.class).hasMessageContaining("API base URI");
        HTTP.verifyRequested(GET, ITEMS_PATH, 1);
    }

    @Test
    void loadNext_shouldSendDefaultAndOverlayHeaders_whenFollowingLink() throws Exception {
        final String nextPath = "/items/second";
        HTTP.stub(
                GET,
                ITEMS_PATH,
                okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-one.json"))
                        .header(LINK, linkNext(HTTP.uri(nextPath + "?page=2"))));
        HTTP.stub(GET, nextPath, okJson(jsonBytes(GitHubPagerWalkIntegrationTest.class, "users-two.json")));

        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClientWireMockSupport.WireMockClient wire = client(HTTP)) {
            final GitHubClient client = wire.client();
            second = GitHubPagers.start(
                            client,
                            client.pagination(),
                            new GitHubPageTarget(ITEMS_PATH, Map.of(), HttpHeaders.of("X-Overlay", "walk-1")),
                            GitHubPageNumberRequest.of(1, 1),
                            GitHubListSpec.<TestUser>builder()
                                    .allowedKinds(GitHubPageRequestKindValidators.PAGE)
                                    .decoder(unused -> response -> GitHubPageBody.of(CODEC.decode(
                                            response.body().mapBytes(bytes -> bytes, () -> new byte[0]), USERS_TYPE)))
                                    .build())
                    .next();
        }

        assertThat(second).isPresent();
        HTTP.verifyHeader(GET, nextPath, API_VERSION, GitHubClient.API_VERSION);
        HTTP.verifyHeader(GET, nextPath, ACCEPT, GitHubClient.DEFAULT_ACCEPT);
        HTTP.verifyHeader(GET, nextPath, AUTHORIZATION, "Bearer " + TestClients.TOKEN);
        HTTP.verifyHeader(GET, nextPath, "X-Overlay", "walk-1");
    }

    private static void assertWalkEndsAfterSecondPage(final GitHubPageRequest request) throws Exception {
        final GitHubPage<TestUser> first;
        final Optional<GitHubPage<TestUser>> second;
        final Optional<GitHubPage<TestUser>> third;
        try (final GitHubClientWireMockSupport.WireMockClient wire = client(HTTP)) {
            first = load(wire.client(), request);
            second = first.next();
            third = second.isPresent() ? second.get().next() : Optional.empty();
        }

        assertSoftly(softly -> {
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items())
                    .singleElement()
                    .extracting(TestUser::login)
                    .isEqualTo("two");
            softly.assertThat(third).isEmpty();
        });
        HTTP.verifyRequested(GET, ITEMS_PATH, 2);
    }

    private static GitHubPage<TestUser> load(final GitHubClient client, final GitHubPageRequest request)
            throws IOException {
        return GitHubPagers.start(
                client,
                client.pagination(),
                new GitHubPageTarget(ITEMS_PATH, Map.of(), HttpHeaders.empty()),
                request,
                GitHubListSpec.<TestUser>builder()
                        .allowedKinds(kindsFor(request))
                        .decoder(unused -> response -> GitHubPageBody.of(
                                CODEC.decode(response.body().mapBytes(bytes -> bytes, () -> new byte[0]), USERS_TYPE)))
                        .build());
    }

    private static Set<GitHubPageRequestKind> kindsFor(final GitHubPageRequest request) {
        return switch (GitHubPageRequestKind.of(request)) {
            case NONE, PAGE -> GitHubPageRequestKindValidators.PAGE;
            case SINCE -> GitHubPageRequestKindValidators.SINCE;
            case CURSOR -> GitHubPageRequestKindValidators.CURSOR;
            case DELIVERY_CURSOR -> GitHubPageRequestKindValidators.DELIVERY_CURSOR;
            case START_INDEX -> GitHubPageRequestKindValidators.START_INDEX;
        };
    }
}
