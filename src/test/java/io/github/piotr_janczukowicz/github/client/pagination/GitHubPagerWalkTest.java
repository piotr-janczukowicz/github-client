package io.github.piotr_janczukowicz.github.client.pagination;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.LINK;
import static io.github.piotr_janczukowicz.test.TestClients.GITHUB_BASE;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.piotr_janczukowicz.github.client.GitHubClient;
import io.github.piotr_janczukowicz.github.client.TestGitHubClients;
import io.github.piotr_janczukowicz.github.client.exception.GitHubApiException;
import io.github.piotr_janczukowicz.github.client.exception.GitHubRateLimitException;
import io.github.piotr_janczukowicz.github.json.GitHubJsonCodecs;
import io.github.piotr_janczukowicz.github.pagination.GitHubCursorRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubDeliveryCursorRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubIssueSearchType;
import io.github.piotr_janczukowicz.github.pagination.GitHubLexicalFallbackReason;
import io.github.piotr_janczukowicz.github.pagination.GitHubPage;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageNumberRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKind;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequestKindValidators;
import io.github.piotr_janczukowicz.github.pagination.GitHubPaginationException;
import io.github.piotr_janczukowicz.github.pagination.GitHubPaginationLimitExceededException;
import io.github.piotr_janczukowicz.github.pagination.GitHubPaginationLoopException;
import io.github.piotr_janczukowicz.github.pagination.GitHubSearchEnvelope;
import io.github.piotr_janczukowicz.github.pagination.GitHubSinceRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubStartIndexRequest;
import io.github.piotr_janczukowicz.github.pagination.GitHubTaskCounts;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpRequest;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.json.JsonType;
import io.github.piotr_janczukowicz.json.JsonTypes;
import io.github.piotr_janczukowicz.pagination.PaginationConfiguration;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.TestHosts;
import io.github.piotr_janczukowicz.test.http.HttpResponses;
import io.github.piotr_janczukowicz.test.http.HttpTransportMocks;
import io.github.piotr_janczukowicz.test.model.TestUser;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import io.github.piotr_janczukowicz.util.FileUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@UnitTest
@Isolated
class GitHubPagerWalkTest {

    private static final JsonType<List<TestUser>> USERS_TYPE = JsonTypes.listOf(TestUser.class);
    private static final JsonCodec JSON_CODEC = GitHubJsonCodecs.defaults();
    private static final GitHubPageRequest FIRST_SINCE = GitHubSinceRequest.first(2);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final Map<String, List<String>> TOPICS_QUERY = Map.of("q", List.of("ruby"));

    private HttpTransport transport;

    @BeforeEach
    void setUpTransport() {
        transport = HttpTransportMocks.mock();
    }

    @Test
    void bindAndLoadFirst_shouldThrow_whenExplicitPerPageExceedsConfig() {
        final GitHubPageRequest request = GitHubSinceRequest.first(101);

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final PaginationConfiguration configured = client.pagination();
            final GitHubListSpec<TestUser> spec = usersSpec(request);

            thrown = catchThrowable(() -> GitHubPagers.start(client, configured, target("/users"), request, spec));
        }

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("perPage")
                .hasMessageContaining("100");
    }

    @Test
    void bindAndLoadFirst_shouldUseConfigPerPage_whenExplicitPerPageOmitted() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("empty-array.json"));

        try (final GitHubClient client = TestClients.client(transport)) {
            load(client, "/users", GitHubPageRequest.bySince());
        }

        verify(transport).execute(any());
        assertThat(captureUri()).hasToString("https://api.github.com/users?" + TestClients.perPageQuery());
    }

    @Test
    void firstPage_shouldOmitPaginationQuery_whenGitHubNoneRequest() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("empty-array.json"));

        try (final GitHubClient client = TestClients.client(transport)) {
            load(client, "/users", GitHubPageRequest.none());
        }

        assertThat(captureUri()).hasToString("https://api.github.com/users");
    }

    @ParameterizedTest
    @EnumSource(LoadNullArg.class)
    void load_shouldThrow_whenRequiredArgumentNull(final LoadNullArg argument) {
        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubClient clientArgument = argument == LoadNullArg.CLIENT ? null : client;

            thrown = catchThrowable(() -> loadWithNull(clientArgument, argument));
        }

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining(argument.parameterName());
    }

    @Test
    void load_shouldThrow_whenDecoderFails() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("empty-array.json"));
        final GitHubPageDecoder<TestUser> decoder = unused -> {
            throw new IllegalStateException("broken");
        };

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final PaginationConfiguration configured = client.pagination();
            final GitHubListSpec<TestUser> spec = spec(FIRST_SINCE, decoder);

            thrown = catchThrowable(() -> GitHubPagers.start(client, configured, target("/users"), FIRST_SINCE, spec));
        }

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("broken");
    }

    @Test
    void loadNext_shouldRemainRetryable_whenDecoderFailsOnNextPage() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink(
                        "users-one-two.json", "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\""))
                .thenReturn(usersResponse("four", "five"))
                .thenReturn(jsonResponse("users-three.json"));
        final AtomicInteger decodes = new AtomicInteger();
        final GitHubPageDecoder<TestUser> decoder = response -> {
            final List<TestUser> items =
                    JSON_CODEC.decode(response.body().mapBytes(bytes -> bytes, () -> new byte[0]), USERS_TYPE);
            if (decodes.incrementAndGet() == 2) {
                throw new IllegalStateException("broken next");
            }
            return GitHubPageBody.of(items);
        };

        final GitHubPage<TestUser> first;
        final Throwable thrown;
        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = firstPage(
                    client, HttpHeaders.empty(), "/repos", Map.of(), GitHubPageNumberRequest.of(1, 2), decoder);
            thrown = catchThrowable(first::next);
            second = first.next();
        }

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("broken next");
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items()).hasSize(1);
        });
    }

    @Test
    void load_shouldThrow_whenNotFoundFirstPageHitsMaxPages() throws Exception {
        final PaginationConfiguration pagination =
                PaginationConfiguration.builder().maxPages(1).build();
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client =
                TestGitHubClients.raw(transport, TestClients.configuration(GITHUB_BASE), pagination)) {
            final PaginationConfiguration configured = client.pagination();
            final GitHubListSpec<TestUser> spec = usersSpec(FIRST_SINCE);

            thrown = catchThrowable(() -> GitHubPagers.start(client, configured, target("/users"), FIRST_SINCE, spec));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("404");
    }

    @Test
    void loadNext_shouldFollowLinkHeader_whenCursorRequestAndNextPresent() throws Exception {
        final HttpResponse firstPage = jsonResponseWithLink(
                "users-one-two.json", "<https://api.github.com/attestations?after=cursor2&per_page=2>; rel=\"next\"");
        final HttpResponse secondPage = jsonResponse("users-three.json");
        when(transport.execute(any())).thenReturn(firstPage, secondPage);

        final GitHubPage<TestUser> first;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = load(client, "/attestations", GitHubCursorRequest.after("cursor1", 2));
        }

        final Optional<GitHubPage<TestUser>> second = first.next();
        final List<URI> uris = captureUris(2);

        assertSoftly(softly -> {
            softly.assertThat(first.items()).hasSize(2);
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items())
                    .singleElement()
                    .extracting(TestUser::login)
                    .isEqualTo("three");
            softly.assertThat(uris.getFirst())
                    .hasToString("https://api.github.com/attestations?after=cursor1&per_page=2");
            softly.assertThat(uris.get(1)).hasToString("https://api.github.com/attestations?after=cursor2&per_page=2");
        });
    }

    @ParameterizedTest
    @MethodSource("io.github.piotr_janczukowicz.test.TestHosts#apiBases")
    void loadNext_shouldFollowLinkHeader_whenNextPresent(final URI base) throws Exception {
        final HttpResponse firstPage = jsonResponseWithLink(
                "users-one-two.json", "<" + base.resolve("users?since=2&per_page=2") + ">; rel=\"next\"");
        final HttpResponse secondPage = jsonResponse("users-three.json");
        when(transport.execute(any())).thenReturn(firstPage, secondPage);

        final GitHubPage<TestUser> first;
        try (final GitHubClient client = TestClients.client(base, transport)) {
            first = load(client, "/users", FIRST_SINCE);
        }

        final Optional<GitHubPage<TestUser>> second = first.next();

        assertSoftly(softly -> {
            softly.assertThat(first.items()).hasSize(2);
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items())
                    .singleElement()
                    .extracting(TestUser::login)
                    .isEqualTo("three");
        });
        verify(transport, times(2)).execute(any());
    }

    @Test
    void loadNext_shouldReturnEmpty_whenSinceRequestAndFullPageWithoutLinkHeader() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("users-one-two.json"));

        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            second = load(client, "/users", FIRST_SINCE).next();
        }

        assertThat(second).isEmpty();
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldReturnEmpty_whenPageRequestAndFullPageWithoutLinkHeader() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("users-one-two.json"));

        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            second = load(client, "/repos", GitHubPageNumberRequest.of(1, 2)).next();
        }

        assertThat(second).isEmpty();
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldThrowLoop_whenNextLinkRepeatsWithParametersInAnotherOrder() throws Exception {
        final String first = "<https://api.github.com/users?since=2&per_page=2>; rel=\"next\"";
        final String reordered = "<https://api.github.com/users?per_page=2&since=2>; rel=\"next\"";
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one-two.json", first))
                .thenReturn(jsonResponseWithLink("users-one-two.json", reordered));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> page = load(client, "/users", FIRST_SINCE);
            final GitHubPage<TestUser> second = page.next().orElseThrow();
            thrown = catchThrowable(second::next);
        }

        assertThat(thrown).isInstanceOf(GitHubPaginationLoopException.class).hasMessageContaining("repeated");
        verify(transport, times(2)).execute(any());
    }

    @Test
    void loadNext_shouldThrowLoop_whenNextLinkRepeats() throws Exception {
        final String link = "<https://api.github.com/users?since=2&per_page=2>; rel=\"next\"";
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one-two.json", link))
                .thenReturn(jsonResponseWithLink("users-one-two.json", link));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/users", FIRST_SINCE);
            final GitHubPage<TestUser> second = first.next().orElseThrow();
            thrown = catchThrowable(second::next);
        }

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationLoopException.class)
                .hasMessageContaining("repeated")
                .hasMessageContaining("https://api.github.com/users")
                .hasMessageNotContaining("since=2");
        verify(transport, times(2)).execute(any());
    }

    @Test
    void loadNext_shouldFollowNextLinks_whenFullPagesHaveNextLinks() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        jsonResponseWithLink(
                                "users-one-two.json", "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\""),
                        withLink(
                                usersResponse("four", "five"),
                                "<https://api.github.com/repos?page=3&per_page=2>; rel=\"next\""),
                        jsonResponse("users-three.json"));

        final ThreePageWalk walk = walkThreePages();

        assertSoftly(softly -> {
            softly.assertThat(walk.uris().getFirst()).hasToString("https://api.github.com/repos?page=1&per_page=2");
            softly.assertThat(walk.uris().get(1)).hasToString("https://api.github.com/repos?page=2&per_page=2");
            softly.assertThat(walk.uris().get(2)).hasToString("https://api.github.com/repos?page=3&per_page=2");
            softly.assertThat(walk.third()).isPresent();
            softly.assertThat(walk.third().orElseThrow().items()).hasSize(1);
        });
    }

    @Test
    void loadNext_shouldThrowPaginationException_whenNextTargetMalformed() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink(
                        "users-one-two.json", "<https://api.github.com/users?q=a b&token=secret>; rel=\"next\""));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/users", GitHubPageNumberRequest.of(1, 2));
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationException.class)
                .hasMessageContaining("https://api.github.com/users")
                .hasMessageNotContaining("secret");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldReturnEmpty_whenLinkPresentWithoutNext() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink(
                        "users-one-two.json", "<https://api.github.com/repos?page=1&per_page=2>; rel=\"prev\""));

        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            second = first.next();
        }

        assertThat(second).isEmpty();
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldRetrySamePage_whenFetchFailsBeforeCommit() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink(
                        "users-one-two.json", "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\""))
                .thenThrow(new IOException("boom"))
                .thenReturn(usersResponse("four", "five"));

        final GitHubPage<TestUser> first;
        final Throwable thrown;
        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            thrown = catchThrowable(first::next);
            second = first.next();
        }

        final List<URI> uris = captureUris(3);

        assertSoftly(softly -> {
            softly.assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("boom");
            softly.assertThat(uris.get(1)).hasToString("https://api.github.com/repos?page=2&per_page=2");
            softly.assertThat(uris.get(2)).hasToString("https://api.github.com/repos?page=2&per_page=2");
            softly.assertThat(second).isPresent();
        });
    }

    @Test
    void loadNext_shouldThrow_whenCalledOnEarlierPageAfterWalk() throws Exception {
        final HttpResponse firstPage =
                jsonResponseWithLink("users-one.json", "<https://api.github.com/users?page=2>; rel=\"next\"");
        final HttpResponse secondPage = jsonResponse("users-two.json");
        when(transport.execute(any())).thenReturn(firstPage, secondPage);

        final GitHubPage<TestUser> first;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = load(client, "/users", GitHubSinceRequest.first(1));
            first.next();
        }

        final Throwable thrown = catchThrowable(first::next);

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("latest page");
    }

    @Test
    void all_shouldThrow_whenCalledOnEarlierPageAfterWalk() throws Exception {
        final HttpResponse firstPage =
                jsonResponseWithLink("users-one.json", "<https://api.github.com/users?page=2>; rel=\"next\"");
        final HttpResponse secondPage = jsonResponse("users-two.json");
        when(transport.execute(any())).thenReturn(firstPage, secondPage);

        final GitHubPage<TestUser> first;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = load(client, "/users", GitHubSinceRequest.first(1));
            first.next();
        }

        final Throwable thrown = catchThrowable(first::all);

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("latest page");
    }

    @ParameterizedTest
    @MethodSource("io.github.piotr_janczukowicz.test.TestHosts#apiBases")
    void loadNext_shouldResolveRelativeLink_whenNextIsRelative(final URI base) throws Exception {
        final HttpResponse firstPage =
                jsonResponseWithLink("users-one.json", "<" + base.getPath() + "users?since=1>; rel=\"next\"");
        final HttpResponse secondPage = jsonResponse("users-two.json");
        when(transport.execute(any())).thenReturn(firstPage, secondPage);

        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(base, transport)) {
            second = load(client, "/users", GitHubSinceRequest.first(1)).next();
        }

        final List<URI> uris = captureUris(2);

        assertSoftly(softly -> {
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items())
                    .singleElement()
                    .extracting(TestUser::login)
                    .isEqualTo("two");
            softly.assertThat(uris.get(1)).isEqualTo(base.resolve("users?since=1"));
        });
    }

    @Test
    void loadNext_shouldReturnEmpty_whenSincePageNotFull() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("users-one.json"));

        final GitHubPage<TestUser> first;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = load(client, "/users", FIRST_SINCE);
        }

        final Optional<GitHubPage<TestUser>> second = first.next();

        assertThat(second).isEmpty();
        verify(transport).execute(any());
    }

    @Test
    void all_shouldReturnAllPages_whenMultiplePagesExist() throws Exception {
        final HttpResponse firstPage =
                jsonResponseWithLink("users-one-two.json", "<https://api.github.com/users?page=2>; rel=\"next\"");
        final HttpResponse secondPage = jsonResponse("users-three.json");
        when(transport.execute(any())).thenReturn(firstPage, secondPage);

        final List<TestUser> all;
        final Throwable afterAll;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/users", FIRST_SINCE);
            all = first.all();
            afterAll = catchThrowable(first::next);
        }

        assertSoftly(softly -> {
            softly.assertThat(all).extracting(TestUser::id).containsExactly(1L, 2L, 3L);
            softly.assertThat(afterAll).isInstanceOf(IllegalStateException.class);
        });
        final Throwable thrown = catchThrowable(all::clear);

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void stream_shouldFetchOnePage_whenFindFirst() throws Exception {
        stubThreeFullPages();

        final Optional<TestUser> first;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = loadThreePageWalk(client).stream().findFirst();
        }

        assertThat(first).isPresent();
        verify(transport, times(1)).execute(any());
    }

    @Test
    void stream_shouldFetchTwoPages_whenLimitedToThreeItems() throws Exception {
        stubThreeFullPages();

        final List<TestUser> items;
        try (final GitHubClient client = TestClients.client(transport)) {
            items = loadThreePageWalk(client).stream().limit(3).toList();
        }

        assertThat(items).hasSize(3);
        verify(transport, times(2)).execute(any());
    }

    @Test
    void stream_shouldFetchAllPages_whenCollected() throws Exception {
        stubThreeFullPages();

        final List<TestUser> items;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> page = loadThreePageWalk(client);
            assertThat(page.stream().spliterator().trySplit()).isNull();
            assertThat(page.pages().spliterator().trySplit()).isNull();
            assertThat(page.pages().spliterator().estimateSize()).isEqualTo(Long.MAX_VALUE);
            items = page.stream().toList();
        }

        assertThat(items).hasSize(6);
        verify(transport, times(3)).execute(any());
    }

    @Test
    void stream_shouldWrapTransportFailure_whenNextPageFails() throws Exception {
        final HttpResponse firstPage = jsonResponseWithLink(
                "users-one-two.json", "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\"");
        when(transport.execute(any())).thenReturn(firstPage).thenThrow(new IOException("boom"));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> page = loadThreePageWalk(client);
            final Stream<TestUser> stream = page.stream();

            thrown = catchThrowable(stream::toList);
        }

        assertThat(thrown)
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("boom");
    }

    @Test
    void all_shouldThrowIOException_whenNextPageFails() throws Exception {
        final HttpResponse firstPage = jsonResponseWithLink(
                "users-one-two.json", "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\"");
        when(transport.execute(any())).thenReturn(firstPage).thenThrow(new IOException("boom"));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> page = loadThreePageWalk(client);

            thrown = catchThrowable(page::all);
        }

        assertThat(thrown).isInstanceOf(IOException.class).hasMessage("boom");
    }

    @Test
    void forEachPage_shouldThrowIOException_whenNextPageFails() throws Exception {
        final HttpResponse firstPage = jsonResponseWithLink(
                "users-one-two.json", "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\"");
        when(transport.execute(any())).thenReturn(firstPage).thenThrow(new IOException("boom"));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> page = loadThreePageWalk(client);

            thrown = catchThrowable(() -> page.forEachPage(unused -> {}));
        }

        assertThat(thrown).isInstanceOf(IOException.class).hasMessage("boom");
    }

    @Test
    void pages_shouldYieldEachPage_whenConsumed() throws Exception {
        stubThreeFullPages();

        final long pageCount;
        try (final GitHubClient client = TestClients.client(transport)) {
            pageCount = loadThreePageWalk(client).pages().count();
        }

        assertThat(pageCount).isEqualTo(3);
        verify(transport, times(3)).execute(any());
    }

    @Test
    void forEachPage_shouldInvokeConsumerForEachPage_whenMultiplePagesExist() throws Exception {
        final HttpResponse firstPage =
                jsonResponseWithLink("users-one-two.json", "<https://api.github.com/users?page=2>; rel=\"next\"");
        final HttpResponse secondPage = jsonResponse("users-three.json");
        when(transport.execute(any())).thenReturn(firstPage, secondPage);
        final AtomicInteger pageCount = new AtomicInteger();

        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/users", FIRST_SINCE);
            first.forEachPage(unused -> pageCount.incrementAndGet());
        }

        assertThat(pageCount).hasValue(2);
    }

    @Test
    void all_shouldThrow_whenMaxPagesReachedWithNextLink() throws Exception {
        assertPaginationLimitExceededAfterTwoPages(GitHubPage::all);
    }

    @Test
    void forEachPage_shouldThrow_whenConsumerNull() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("users-one.json"));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> page = load(client, "/users", FIRST_SINCE);

            thrown = catchThrowable(() -> page.forEachPage(null));
        }

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("consumer");
    }

    @Test
    void forEachPage_shouldThrow_whenMaxPagesReachedWithNextLink() throws Exception {
        assertPaginationLimitExceededAfterTwoPages(page -> page.forEachPage(unused -> {}));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "<https://api.github.com/users?page=2>; rel=\"next\"",
                "<https://evil.example/users?page=2>; rel=\"next\""
            })
    void loadNext_shouldThrow_whenMaxPagesReachedWithNextLink(final String nextLink) throws Exception {
        final PaginationConfiguration pagination =
                PaginationConfiguration.builder().maxPages(1).build();
        when(transport.execute(any())).thenReturn(jsonResponseWithLink("users-one.json", nextLink));

        final Throwable thrown;
        try (final GitHubClient client =
                TestGitHubClients.raw(transport, TestClients.configuration(GITHUB_BASE), pagination)) {
            final GitHubPage<TestUser> first = load(client, "/users", FIRST_SINCE);
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationLimitExceededException.class)
                .extracting(
                        ex -> ((GitHubPaginationLimitExceededException) ex).maxPages(),
                        ex -> ((GitHubPaginationLimitExceededException) ex).pageCount())
                .containsExactly(1, 1);
        verify(transport).execute(any());
    }

    @Test
    void loadNext_shouldReturnEmpty_whenMaxPagesReachedWithoutNextLink() throws Exception {
        final PaginationConfiguration pagination =
                PaginationConfiguration.builder().maxPages(1).build();
        when(transport.execute(any())).thenReturn(jsonResponse("users-one.json"));

        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client =
                TestGitHubClients.raw(transport, TestClients.configuration(GITHUB_BASE), pagination)) {
            final GitHubPage<TestUser> first = load(client, "/users", GitHubPageRequest.none());
            second = first.next();
        }

        assertThat(second).isEmpty();
        verify(transport).execute(any());
    }

    @Test
    void bindAndLoadFirst_shouldThrow_whenResponseIsNotFound() throws Exception {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final PaginationConfiguration configured = client.pagination();
            final GitHubListSpec<TestUser> spec = usersSpec(FIRST_SINCE);

            thrown = catchThrowable(() -> GitHubPagers.start(client, configured, target("/users"), FIRST_SINCE, spec));
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("404");
    }

    @Test
    void loadNext_shouldThrowPaginationException_whenNextHostDiffers() throws Exception {
        final HttpResponse firstPage =
                jsonResponseWithLink("users-one.json", "<https://evil.example/users?page=2>; rel=\"next\"");
        when(transport.execute(any())).thenReturn(firstPage);

        final GitHubPage<TestUser> first;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = load(client, "/users", GitHubSinceRequest.first(1));
        }

        final Throwable thrown = catchThrowable(first::next);

        assertThat(thrown).isInstanceOf(GitHubPaginationException.class).hasMessageContaining("API base URI");
    }

    @Test
    void bindAndLoadFirst_shouldSendPageQuery_whenPageRequestProvided() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("empty-array.json"));

        try (final GitHubClient client = TestClients.client(transport)) {
            load(client, "/repos", GitHubPageNumberRequest.of(2, 10));
        }

        assertThat(captureUri()).hasToString("https://api.github.com/repos?page=2&per_page=10");
    }

    @Test
    void loadNext_shouldThrow_whenNextResponseIsNotFound() throws Exception {
        final HttpResponse firstPage =
                jsonResponseWithLink("users-one.json", "<https://api.github.com/users?page=2>; rel=\"next\"");
        final HttpResponse notFound =
                new HttpResponse(HttpStatus.NOT_FOUND.code(), HttpHeaders.empty(), HttpBody.absent());
        when(transport.execute(any())).thenReturn(firstPage, notFound);

        final GitHubPage<TestUser> first;
        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = load(client, "/users", GitHubSinceRequest.first(1));
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown).isInstanceOf(GitHubApiException.class).hasMessageContaining("404");
        verify(transport, times(2)).execute(any());
    }

    @Test
    void load_shouldThrow_whenDecoderReturnsNull() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("users-one.json"));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final PaginationConfiguration configured = client.pagination();
            final GitHubListSpec<TestUser> spec = spec(GitHubPageRequest.none(), unused -> null);

            thrown = catchThrowable(
                    () -> GitHubPagers.start(client, configured, target("/users"), GitHubPageRequest.none(), spec));
        }

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("items");
    }

    @Test
    void items_shouldBeUnmodifiable_whenCalled() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("users-one.json"));

        final GitHubPage<TestUser> page;
        try (final GitHubClient client = TestClients.client(transport)) {
            page = load(client, "/users", GitHubSinceRequest.first(1));
        }

        final Throwable thrown = catchThrowable(() -> page.items().clear());

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void totalCount_shouldBeEmpty_whenArrayDecoder() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("users-one.json"));

        final GitHubPage<TestUser> page;
        try (final GitHubClient client = TestClients.client(transport)) {
            page = load(client, "/users", GitHubSinceRequest.first(1));
        }

        assertThat(page.totalCount()).isNull();
    }

    @Test
    void totalCount_shouldExposeWrapperCount_whenDecoderProvidesTotal() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("users-one.json"));

        final GitHubPage<TestUser> page;
        try (final GitHubClient client = TestClients.client(transport)) {
            page = firstPage(
                    client,
                    HttpHeaders.empty(),
                    "/users",
                    Map.of(),
                    GitHubPageRequest.none(),
                    response -> GitHubPageBody.of(
                            JSON_CODEC.decode(response.body().mapBytes(bytes -> bytes, () -> new byte[0]), USERS_TYPE),
                            9));
        }

        assertThat(page.totalCount()).isEqualTo(9);
    }

    @Test
    void start_shouldThrow_whenKindNotAllowed() {
        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            thrown = catchThrowable(() -> GitHubPagers.start(
                    client,
                    client.pagination(),
                    new GitHubPageTarget("/users", Map.of(), HttpHeaders.empty()),
                    GitHubPageRequest.byCursor(),
                    GitHubListSpec.<TestUser>builder()
                            .allowedKinds(GitHubPageRequestKindValidators.PAGE)
                            .decoder(unused -> response -> GitHubPageBody.of(List.of()))
                            .build()));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cursor");
    }

    @Test
    void startSearch_shouldThrow_whenKindNotAllowed() {
        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            thrown = catchThrowable(() -> GitHubPagers.startSearch(
                    client,
                    client.pagination(),
                    new GitHubPageTarget("/search/topics", TOPICS_QUERY, HttpHeaders.empty()),
                    GitHubPageRequest.byCursor(),
                    searchDecoder()));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cursor");
    }

    @Test
    void search_shouldExposeEnvelopeAndAdvancePage_whenNextLinkPresent() throws Exception {
        when(transport.execute(any()))
                .thenReturn(searchJsonWithLink(
                        """
                        {"total_count":2,"incomplete_results":false,"items":[{"name":"a"}]}
                        """, "<https://api.github.com/search/topics?q=ruby&page=2&per_page=1>; rel=\"next\""))
                .thenReturn(ok("""
                        {"total_count":2,"incomplete_results":true,"items":[{"name":"b"}]}
                        """));

        final GitHubPage<String> first;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = loadSearch(client, GitHubPageNumberRequest.of(1, 1));
        }

        assertThat(first.totalCount()).isEqualTo(2);
        assertThat(first.areResultsIncomplete()).isFalse();
        assertThat(first.items()).containsExactly("a");

        final GitHubPage<String> second = first.next().orElseThrow();
        final Optional<GitHubPage<String>> third = second.next();

        assertSoftly(softly -> {
            softly.assertThat(second.items()).containsExactly("b");
            softly.assertThat(second.areResultsIncomplete()).isTrue();
            softly.assertThat(third).isEmpty();
        });
    }

    @Test
    void search_shouldThrow_whenDecoderReturnsNull() throws Exception {
        when(transport.execute(any())).thenReturn(ok("""
                {"total_count":1,"incomplete_results":false,"items":[{"name":"a"}]}
                """));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            thrown = catchThrowable(() -> GitHubPagers.startSearch(
                    client,
                    client.pagination(),
                    new GitHubPageTarget("/search/topics", TOPICS_QUERY, HttpHeaders.empty()),
                    GitHubPageNumberRequest.of(1, 1),
                    unused -> null));
        }

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessageContaining("envelope");
    }

    @Test
    void search_shouldStopAtSearchResultLimit_whenTotalCountExceedsLimit() throws Exception {
        when(transport.execute(any()))
                .thenAnswer(invocation -> searchItems(
                        5000,
                        100,
                        pageFromUri(invocation.getArgument(0, HttpRequest.class).uri())));

        final GitHubPage<String> first;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = loadSearch(client, GitHubPageNumberRequest.of(1, 100));
            first.all();
            final Throwable thrown = catchThrowable(first::next);

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
        }

        verify(transport, times(10)).execute(any());
    }

    @Test
    void search_shouldMakeNoFurtherRequest_whenStartingPageAlreadyReachesTotal() throws Exception {
        when(transport.execute(any())).thenReturn(searchItems(90, 30));

        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<String> first = loadSearch(client, GitHubPageNumberRequest.of(3, 30));
            assertThat(first.next()).isEmpty();
        }

        verify(transport).execute(any());
    }

    @Test
    void search_shouldStopAfterPartialLastPage_whenTotalCountIsSearchLimit() throws Exception {
        when(transport.execute(any())).thenAnswer(invocation -> {
            final URI uri = invocation.getArgument(0, HttpRequest.class).uri();
            final int page = pageFromUri(uri);
            final int remaining = Math.min(30, 1000 - (page - 1) * 30);
            return searchItems(1000, remaining, page);
        });

        final GitHubPage<String> last;
        try (final GitHubClient client = TestClients.client(transport)) {
            GitHubPage<String> page = loadSearch(client, GitHubPageNumberRequest.of(1, 30));
            Optional<GitHubPage<String>> next = page.next();
            while (next.isPresent()) {
                page = next.get();
                next = page.next();
            }
            last = page;
        }

        verify(transport, times(34)).execute(any());
        assertThat(last.items()).hasSize(10);
        assertThat(last.pageCount()).isEqualTo(34);
    }

    @Test
    void search_shouldReturnEmptyLexicalFallbackReasons_whenEnvelopeFieldNull() throws Exception {
        when(transport.execute(any())).thenReturn(ok("""
                        {"total_count":1,"incomplete_results":false,"items":[{"name":"a"}]}
                        """));

        final GitHubPage<String> page;
        try (final GitHubClient client = TestClients.client(transport)) {
            page = loadSearch(client, GitHubPageNumberRequest.of(1, 1));
        }

        assertThat(page.lexicalFallbackReasons()).isEmpty();
    }

    @Test
    void search_shouldExposeIssueSearchFields_whenEnvelopeHasThem() throws Exception {
        when(transport.execute(any())).thenReturn(ok("""
                        {"total_count":1,"incomplete_results":false,"search_type":"semantic","lexical_fallback_reason":["quoted_text"],"items":[{"name":"a"}]}
                        """));

        final GitHubPage<String> page;
        try (final GitHubClient client = TestClients.client(transport)) {
            page = loadSearch(client, GitHubPageNumberRequest.of(1, 1));
        }

        assertSoftly(softly -> {
            softly.assertThat(page.searchType()).isEqualTo(GitHubIssueSearchType.SEMANTIC);
            softly.assertThat(page.lexicalFallbackReasons()).containsExactly(GitHubLexicalFallbackReason.QUOTED_TEXT);
            softly.assertThat(catchThrowable(() -> page.lexicalFallbackReasons().clear()))
                    .isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test
    void search_shouldReturnEmpty_whenFullPageWithoutLinkHeader() throws Exception {
        when(transport.execute(any())).thenReturn(ok("""
                        {"total_count":3,"incomplete_results":false,"items":[{"name":"a"},{"name":"b"}]}
                        """));

        final Optional<GitHubPage<String>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            second = loadSearch(client, GitHubPageNumberRequest.of(1, 2)).next();
        }

        assertThat(second).isEmpty();
        verify(transport, times(1)).execute(any());
    }

    @Test
    void firstPage_shouldSendPageWithoutPerPage_whenSpecOmitsPerPage() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("empty-array.json"));

        try (final GitHubClient client = TestClients.client(transport)) {
            GitHubPagers.start(
                    client,
                    client.pagination(),
                    new GitHubPageTarget("/users", Map.of(), HttpHeaders.empty()),
                    GitHubPageRequest.byPage(),
                    GitHubListSpec.<TestUser>builder()
                            .allowedKinds(GitHubPageRequestKindValidators.PAGE)
                            .decoder(unused -> response -> GitHubPageBody.of(List.of()))
                            .sendPerPage(false)
                            .build());
        }

        assertThat(captureUri()).hasToString("https://api.github.com/users?page=1");
    }

    @ParameterizedTest
    @MethodSource("withoutPerPageFirstQueries")
    void firstPage_shouldOmitPerPage_whenSpecOmitsPerPage(
            final GitHubPageRequest request, final String path, final String expectedUri) throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("empty-array.json"));

        try (final GitHubClient client = TestClients.client(transport)) {
            GitHubPagers.start(
                    client,
                    client.pagination(),
                    new GitHubPageTarget(path, Map.of(), HttpHeaders.empty()),
                    request,
                    GitHubListSpec.<TestUser>builder()
                            .allowedKinds(kindsFor(request))
                            .decoder(unused -> response -> GitHubPageBody.of(List.of()))
                            .sendPerPage(false)
                            .build());
        }

        assertThat(captureUri()).hasToString(expectedUri);
    }

    @Test
    void search_shouldFollowLink_whenNextPresentAndCapNotReached() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        searchJsonWithLink("""
                        {"total_count":2,"incomplete_results":false,"items":[{"name":"a"}]}
                        """, "<https://api.github.com/search/topics?q=ruby&page=2>; rel=\"next\""))
                .thenReturn(ok("""
                        {"total_count":2,"incomplete_results":true,"items":[{"name":"b"}]}
                        """));

        final GitHubPage<String> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            second = loadSearch(client, GitHubPageNumberRequest.of(1, 1)).next().orElseThrow();
        }

        final List<URI> uris = captureUris(2);
        assertSoftly(softly -> {
            softly.assertThat(second.items()).containsExactly("b");
            softly.assertThat(uris.get(1)).hasToString("https://api.github.com/search/topics?q=ruby&page=2");
        });
    }

    @Test
    void search_shouldIgnoreLink_whenSearchCapReached() throws Exception {
        when(transport.execute(any()))
                .thenReturn(searchJsonWithLink(
                        "{\"total_count\":5000,\"incomplete_results\":false,\"items\":" + searchItemArray(100) + "}",
                        "<https://api.github.com/search/topics?q=ruby&page=11>; rel=\"next\""));

        try (final GitHubClient client = TestClients.client(transport)) {
            assertThat(loadSearch(client, GitHubPageNumberRequest.of(10, 100)).next())
                    .isEmpty();
        }

        verify(transport).execute(any());
    }

    @Test
    void search_shouldStop_whenLinkPresentWithoutNextAndFullPageUnderCap() throws Exception {
        when(transport.execute(any()))
                .thenReturn(searchJsonWithLink(
                        "{\"total_count\":200,\"incomplete_results\":false,\"items\":" + searchItemArray(100) + "}",
                        "<https://api.github.com/search/topics?q=ruby&page=2>; rel=\"last\""));

        try (final GitHubClient client = TestClients.client(transport)) {
            assertThat(loadSearch(client, GitHubPageNumberRequest.of(1, 100)).next())
                    .isEmpty();
        }

        verify(transport).execute(any());
    }

    @Test
    void search_shouldFollowLink_whenShortPageHasNext() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        searchJsonWithLink("""
                        {"total_count":3,"incomplete_results":false,"items":[{"name":"a"}]}
                        """, "<https://api.github.com/search/topics?q=ruby&page=2>; rel=\"next\""))
                .thenReturn(ok("""
                        {"total_count":3,"incomplete_results":true,"items":[{"name":"b"},{"name":"c"}]}
                        """));

        final GitHubPage<String> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            second = loadSearch(client, GitHubPageNumberRequest.of(1, 2)).next().orElseThrow();
        }

        final List<URI> uris = captureUris(2);
        assertSoftly(softly -> {
            softly.assertThat(second.items()).containsExactly("b", "c");
            softly.assertThat(uris.get(1)).hasToString("https://api.github.com/search/topics?q=ruby&page=2");
        });
    }

    @ParameterizedTest
    @MethodSource("noneSearchTotals")
    void search_shouldReturnAllReachableItems_whenNoneRequestUsesGitHubDefaultPageSize(
            final int totalCount, final int expectedItems) throws Exception {
        stubGithubDefaultSearch(totalCount);

        final List<String> items;
        final URI firstUri;
        try (final GitHubClient client = TestClients.client(transport)) {
            items = loadSearch(client, GitHubPageRequest.none()).all();
        }
        firstUri = captureFirstUri();

        assertSoftly(softly -> {
            softly.assertThat(firstUri.getQuery()).doesNotContain("per_page");
            softly.assertThat(items).hasSize(expectedItems);
        });
    }

    @Test
    void search_shouldStopAtCap_whenNoneRequestFollowsLink() throws Exception {
        stubGithubDefaultSearch(5000);
        final int githubDefaultPerPage = 30;
        final int expectedPages = (GitHubPage.SEARCH_RESULT_LIMIT + githubDefaultPerPage - 1) / githubDefaultPerPage;

        int pages = 1;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<String> page = loadSearch(client, GitHubPageRequest.none());
            Optional<GitHubPage<String>> next = page.next();
            while (next.isPresent()) {
                pages++;
                assertThat(pages).isLessThanOrEqualTo(expectedPages);
                next = next.get().next();
            }
        }

        verify(transport, times(expectedPages)).execute(any());
        assertThat(pages).isEqualTo(expectedPages);
        assertThat(captureFirstUri().getQuery()).doesNotContain("per_page");
    }

    @Test
    void loadNext_shouldFollowLinkUnderGhesBasePath_whenNextIsAbsolute() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink(
                        "users-one.json", "<https://ghe.example/api/v3/users?page=2>; rel=\"next\""))
                .thenReturn(jsonResponse("users-two.json"));
        final HttpClientConfiguration config =
                TestClients.configurationBuilder(TestHosts.GHES).build();

        try (final GitHubClient client = TestGitHubClients.raw(transport, config, TestClients.auth())) {
            load(client, "/users", GitHubPageNumberRequest.of(1, 1)).next();
        }

        final List<URI> uris = captureUris(2);
        assertThat(uris.get(1)).hasToString("https://ghe.example/api/v3/users?page=2");
    }

    @Test
    void loadNext_shouldFollowLink_whenRelIsNotFirstParameterAndHasSeveralNames() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink(
                        "users-one.json", "<https://api.github.com/users?since=1>; title=\"more\"; rel=\"next last\""))
                .thenReturn(jsonResponse("users-two.json"));

        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            second = load(client, "/users", GitHubSinceRequest.first(1)).next();
        }

        assertThat(second).isPresent();
        assertThat(captureUris(2).get(1)).hasToString("https://api.github.com/users?since=1");
    }

    @Test
    void loadNext_shouldFollowRootRelativeLink_whenGhesBasePathAndLinkIncludesIt() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one.json", "</api/v3/users?page=2>; rel=\"next\""))
                .thenReturn(jsonResponse("users-two.json"));

        try (final GitHubClient client = ghesClient()) {
            load(client, "/users", GitHubPageNumberRequest.of(1, 1)).next();
        }

        assertThat(captureUris(2).get(1)).hasToString("https://ghe.example/api/v3/users?page=2");
    }

    @Test
    void loadNext_shouldResolveAgainstRequestUri_whenGhesLinkIsPathRelative() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one.json", "<more?page=2>; rel=\"next\""))
                .thenReturn(jsonResponse("users-two.json"));

        try (final GitHubClient client = ghesClient()) {
            load(client, "/users/list", GitHubPageNumberRequest.of(1, 1)).next();
        }

        assertThat(captureUris(2).get(1)).hasToString("https://ghe.example/api/v3/users/more?page=2");
    }

    @Test
    void loadNext_shouldResolveSecondRelativeLinkAgainstFollowedLink_whenWalkContinues() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one.json", "<deep/one?page=2>; rel=\"next\""))
                .thenReturn(jsonResponseWithLink("users-two.json", "<two?page=3>; rel=\"next\""))
                .thenReturn(jsonResponse("users-two.json"));

        try (final GitHubClient client = ghesClient()) {
            load(client, "/users", GitHubPageNumberRequest.of(1, 1))
                    .next()
                    .orElseThrow()
                    .next();
        }

        assertThat(captureUris(3).get(2)).hasToString("https://ghe.example/api/v3/deep/two?page=3");
    }

    @Test
    void loadNext_shouldThrow_whenRootRelativeLinkLeavesGhesBasePath() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one.json", "</users?page=2>; rel=\"next\""));

        final Throwable thrown;
        try (final GitHubClient client = ghesClient()) {
            final GitHubPage<TestUser> first = load(client, "/users", GitHubPageNumberRequest.of(1, 1));
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown).isInstanceOf(GitHubPaginationException.class).hasMessageContaining("API base URI");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void start_shouldReturnEmptyPage_whenFirstPageIsNoContent() throws Exception {
        when(transport.execute(any())).thenReturn(noContent());

        final GitHubPage<TestUser> first;
        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            second = first.next();
        }

        assertSoftly(softly -> {
            softly.assertThat(first.items()).isEmpty();
            softly.assertThat(first.totalCount()).isNull();
            softly.assertThat(second).isEmpty();
        });
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldReturnEmptyPageAndEnd_whenLinkedPageIsNoContent() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        jsonResponseWithLink(
                                "users-one-two.json", "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\""),
                        noContent());

        final GitHubPage<TestUser> second;
        final Optional<GitHubPage<TestUser>> third;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            second = first.next().orElseThrow();
            third = second.next();
        }

        assertSoftly(softly -> {
            softly.assertThat(second.items()).isEmpty();
            softly.assertThat(second.totalCount()).isNull();
            softly.assertThat(third).isEmpty();
        });
        verify(transport, times(2)).execute(any());
    }

    @Test
    void loadNext_shouldFollowLink_whenNoContentPageCarriesLinkNext() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        new HttpResponse(
                                HttpStatus.NO_CONTENT.code(),
                                HttpHeaders.of(LINK, "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\""),
                                HttpBody.absent()),
                        jsonResponse("users-three.json"));

        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            second = load(client, "/repos", GitHubPageNumberRequest.of(1, 2)).next();
        }

        assertSoftly(softly -> {
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items()).hasSize(1);
        });
        assertThat(captureUris(2).get(1)).hasToString("https://api.github.com/repos?page=2&per_page=2");
    }

    @Test
    void start_shouldThrow_whenOkResponseHasNoBody() throws Exception {
        when(transport.execute(any()))
                .thenReturn(new HttpResponse(HttpStatus.OK.code(), HttpHeaders.empty(), HttpBody.absent()));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final PaginationConfiguration configured = client.pagination();
            final GitHubListSpec<String> spec = namesSpec();

            thrown = catchThrowable(
                    () -> GitHubPagers.start(client, configured, target("/repos"), GitHubPageRequest.byPage(), spec));
        }

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("no body");
    }

    @Test
    void loadNext_shouldFollowNext_whenLinkSentAsTwoHeaderLines() throws Exception {
        final HttpHeaders twoLines = HttpHeaders.fromWire(Map.of(
                LINK,
                List.of(
                        "<https://api.github.com/repos?page=1&per_page=2>; rel=\"prev\"",
                        "<https://api.github.com/repos?page=3&per_page=2>; rel=\"next\"")));
        when(transport.execute(any()))
                .thenReturn(
                        new HttpResponse(HttpStatus.OK.code(), twoLines, HttpBody.of(usersBody("one", "two"))),
                        jsonResponse("users-three.json"));

        try (final GitHubClient client = TestClients.client(transport)) {
            load(client, "/repos", GitHubPageNumberRequest.of(2, 2)).next();
        }

        assertThat(captureUris(2).get(1)).hasToString("https://api.github.com/repos?page=3&per_page=2");
    }

    @Test
    void loadNext_shouldFollowNext_whenLinkHeaderNameIsLowerCase() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        HttpResponses.of(
                                HttpStatus.OK.code(),
                                Map.of(
                                        "link",
                                        List.of("<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\"")),
                                HttpBody.of(usersBody("one", "two"))),
                        jsonResponse("users-three.json"));

        try (final GitHubClient client = TestClients.client(transport)) {
            load(client, "/repos", GitHubPageNumberRequest.of(1, 2)).next();
        }

        assertThat(captureUris(2).get(1)).hasToString("https://api.github.com/repos?page=2&per_page=2");
    }

    @Test
    void loadNext_shouldRequestLinkVerbatim_whenNextPointsToDifferentPath() throws Exception {
        final String link = "https://api.github.com/repositories/1300192/issues?page=2&per_page=100";
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one-two.json", "<" + link + ">; rel=\"next\""))
                .thenReturn(jsonResponse("users-three.json"));

        try (final GitHubClient client = TestClients.client(transport)) {
            load(client, "/repos/o/r/issues", GitHubPageNumberRequest.of(1, 100))
                    .next();
        }

        assertThat(captureUris(2).get(1)).hasToString(link);
    }

    @Test
    void loadNext_shouldThrowPaginationException_whenRelativeLinkCannotBeResolved() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one-two.json", "<//api.github.com:99999/repos>; rel=\"next\""));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown).isInstanceOf(GitHubPaginationException.class).hasMessageContaining("Link");
    }

    @ParameterizedTest
    @MethodSource("linksThatChangeSchemeOrPort")
    void loadNext_shouldThrowPaginationException_whenLinkChangesSchemeOrPort(final URI base, final String link)
            throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one-two.json", "<" + link + ">; rel=\"next\""));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(base, transport)) {
            final GitHubPage<TestUser> first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationException.class)
                .hasMessageContaining("outside the API base URI");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldHintAtTheHostname_whenLinkDiffersOnlyByHost() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink(
                        "users-one-two.json", "<https://internal.example/api/v3/repos?page=2>; rel=\"next\""));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(TestHosts.GHES, transport)) {
            final GitHubPage<TestUser> first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationException.class)
                .hasMessageContaining("outside the API base URI")
                .hasMessageContaining("configure the client with the instance's configured hostname");
    }

    @Test
    void loadNext_shouldNotHint_whenLinkAlsoLeavesTheBasePath() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink(
                        "users-one-two.json", "<https://internal.example/elsewhere/repos?page=2>; rel=\"next\""));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(TestHosts.GHES, transport)) {
            final GitHubPage<TestUser> first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationException.class)
                .hasMessageContaining("outside the API base URI")
                .hasMessageNotContaining("configured hostname");
    }

    @Test
    void loadNext_shouldRetrySameLink_whenNextPageFailsWithForbidden() throws Exception {
        final Throwable thrown = failNextPageThenRetry(HttpStatus.FORBIDDEN);

        assertThat(thrown).isInstanceOf(GitHubApiException.class);
    }

    @Test
    void loadNext_shouldRetrySameLink_whenNextPageFailsWithTooManyRequests() throws Exception {
        final Throwable thrown = failNextPageThenRetry(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(thrown).isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    void loadNext_shouldFollowLinkOnNextPage_whenEmptyPageStillHasNext() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        jsonResponseWithLink(
                                "empty-array.json", "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\""),
                        jsonResponse("users-three.json"));

        final GitHubPage<TestUser> first;
        final Optional<GitHubPage<TestUser>> second;
        final Optional<GitHubPage<TestUser>> third;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            second = first.next();
            third = second.isPresent() ? second.get().next() : Optional.empty();
        }

        assertSoftly(softly -> {
            softly.assertThat(first.items()).isEmpty();
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items()).hasSize(1);
            softly.assertThat(third).isEmpty();
        });
        verify(transport, times(2)).execute(any());
    }

    @Test
    void loadNext_shouldExposeTotalCountPerPage_whenWrapperWalkFollowsLink() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        searchJsonWithLink(
                                "{\"total_count\":5,\"names\":[\"a\",\"b\"]}",
                                "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\""),
                        ok("{\"total_count\":4,\"names\":[\"c\"]}"));

        final GitHubPage<String> first;
        final Optional<GitHubPage<String>> second;
        final Optional<GitHubPage<String>> third;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = startNames(client, GitHubPageRequest.byPage());
            second = first.next();
            third = second.isPresent() ? second.get().next() : Optional.empty();
        }

        assertSoftly(softly -> {
            softly.assertThat(first.items()).containsExactly("a", "b");
            softly.assertThat(first.totalCount()).isEqualTo(5);
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items()).containsExactly("c");
            softly.assertThat(second.orElseThrow().totalCount()).isEqualTo(4);
            softly.assertThat(third).isEmpty();
        });
        assertThat(captureUris(2).get(1)).hasToString("https://api.github.com/repos?page=2&per_page=2");
    }

    @Test
    void search_shouldFollowLink_whenTotalCountDriftsBelowItemsReceived() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        searchJsonWithLink(
                                "{\"total_count\":1,\"incomplete_results\":true,\"items\":[{\"name\":\"a\"},{\"name\":\"b\"}]}",
                                "<https://api.github.com/search/topics?q=ruby&page=2&per_page=2>; rel=\"next\""),
                        ok("{\"total_count\":1,\"incomplete_results\":false,\"items\":[{\"name\":\"c\"}]}"));

        final GitHubPage<String> first;
        final Optional<GitHubPage<String>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = loadSearch(client, GitHubPageNumberRequest.of(1, 2));
            second = first.next();
        }

        assertSoftly(softly -> {
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items()).containsExactly("c");
        });
        verify(transport, times(2)).execute(any());
    }

    @Test
    void search_shouldFollowLink_whenLaterPageReportsSmallerTotalCount() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        searchJsonWithLink(
                                "{\"total_count\":3,\"incomplete_results\":false,\"items\":[{\"name\":\"a\"},{\"name\":\"b\"}]}",
                                "<https://api.github.com/search/topics?q=ruby&page=2&per_page=2>; rel=\"next\""),
                        ok("{\"total_count\":1,\"incomplete_results\":false,\"items\":[{\"name\":\"c\"}]}"));

        final Optional<GitHubPage<String>> second;
        final Optional<GitHubPage<String>> third;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<String> first = loadSearch(client, GitHubPageNumberRequest.of(1, 2));
            second = first.next();
            third = second.isPresent() ? second.get().next() : Optional.empty();
        }

        assertSoftly(softly -> {
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().items()).containsExactly("c");
            softly.assertThat(third).isEmpty();
        });
        verify(transport, times(2)).execute(any());
    }

    @Test
    void loadNext_shouldNotCompareBodies_whenPageWasFollowedFromLink() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        jsonResponseWithLink(
                                "users-one-two.json", "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\""),
                        jsonResponse("users-one-two.json"));

        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            second = load(client, "/repos", GitHubPageNumberRequest.of(1, 2)).next();
        }

        assertThat(second).isPresent();
    }

    private Throwable failNextPageThenRetry(final HttpStatus status) throws Exception {
        final String link = "https://api.github.com/repos?page=2&per_page=2";
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one-two.json", "<" + link + ">; rel=\"next\""))
                .thenReturn(HttpResponses.of(
                        status.code(),
                        Map.of(),
                        HttpBody.of("{\"message\":\"nope\"}".getBytes(StandardCharsets.UTF_8))))
                .thenReturn(jsonResponse("users-three.json"));

        final Throwable thrown;
        final Optional<GitHubPage<TestUser>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            thrown = catchThrowable(first::next);
            second = first.next();
        }

        final List<URI> uris = captureUris(3);
        assertSoftly(softly -> {
            softly.assertThat(uris.get(1)).hasToString(link);
            softly.assertThat(uris.get(2)).hasToString(link);
            softly.assertThat(second).isPresent();
            softly.assertThat(second.orElseThrow().pageCount()).isEqualTo(2);
        });
        return thrown;
    }

    private static HttpResponse noContent() {
        return new HttpResponse(HttpStatus.NO_CONTENT.code(), HttpHeaders.empty(), HttpBody.absent());
    }

    record NamesPage(int totalCount, List<String> names) {}

    record ScimNamesPage(List<String> names, int totalResults) {}

    private static GitHubPage<String> startIndexNames(final GitHubClient client, final GitHubPageRequest request)
            throws IOException {
        return startIndexNames(client, request, HttpHeaders.empty());
    }

    private static GitHubPage<String> startIndexNames(
            final GitHubClient client, final GitHubPageRequest request, final HttpHeaders overlay) throws IOException {
        return GitHubPagers.start(
                client,
                client.pagination(),
                new GitHubPageTarget("/scim", Map.of(), overlay),
                request,
                GitHubListSpec.startIndexWrapper(
                        ScimNamesPage.class, ScimNamesPage::names, ScimNamesPage::totalResults));
    }

    private static GitHubPage<String> startNames(final GitHubClient client, final GitHubPageRequest request)
            throws IOException {
        return GitHubPagers.start(
                client,
                client.pagination(),
                new GitHubPageTarget("/repos", Map.of(), HttpHeaders.empty()),
                request,
                GitHubListSpec.wrapper(
                        GitHubPageRequestKindValidators.PAGE,
                        NamesPage.class,
                        NamesPage::names,
                        NamesPage::totalCount));
    }

    private GitHubClient ghesClient() {
        final HttpClientConfiguration config =
                TestClients.configurationBuilder(TestHosts.GHES).build();
        return TestGitHubClients.raw(transport, config, TestClients.auth());
    }

    private static Stream<Arguments> linksThatChangeSchemeOrPort() {
        return TestHosts.apiBases()
                .flatMap(base -> Stream.of(
                        Arguments.of(
                                base,
                                "http"
                                        + base.toString()
                                                .substring(base.getScheme().length())),
                        Arguments.of(
                                base,
                                base.getScheme() + "://" + base.getHost() + ":444" + base.getPath() + "repos?page=2")));
    }

    private static GitHubPageTarget target(final String path) {
        return new GitHubPageTarget(path, Map.of(), HttpHeaders.empty());
    }

    private static <T> GitHubListSpec<T> spec(final GitHubPageRequest request, final GitHubPageDecoder<T> decoder) {
        return GitHubListSpec.<T>builder()
                .allowedKinds(kindsFor(request))
                .decoder(decoder == null ? null : unused -> decoder)
                .sendPerPage(true)
                .build();
    }

    private static GitHubListSpec<TestUser> usersSpec(final GitHubPageRequest request) {
        return spec(
                request,
                response -> GitHubPageBody.of(
                        JSON_CODEC.decode(response.body().mapBytes(bytes -> bytes, () -> new byte[0]), USERS_TYPE)));
    }

    private static GitHubListSpec<String> namesSpec() {
        return GitHubListSpec.wrapper(
                GitHubPageRequestKindValidators.PAGE, NamesPage.class, NamesPage::names, NamesPage::totalCount);
    }

    private static GitHubListSpec<String> scimNamesSpec() {
        return GitHubListSpec.startIndexWrapper(ScimNamesPage.class, ScimNamesPage::names, ScimNamesPage::totalResults);
    }

    private GitHubPage<TestUser> load(final GitHubClient client, final String path, final GitHubPageRequest request)
            throws IOException {
        return firstPage(
                client,
                HttpHeaders.empty(),
                path,
                Map.of(),
                request,
                response -> GitHubPageBody.of(
                        JSON_CODEC.decode(response.body().mapBytes(bytes -> bytes, () -> new byte[0]), USERS_TYPE)));
    }

    private static <T> GitHubPage<T> firstPage(
            final GitHubClient client,
            final HttpHeaders overlayHeaders,
            final String path,
            final Map<String, List<String>> baseQuery,
            final GitHubPageRequest request,
            final GitHubPageDecoder<T> decoder)
            throws IOException {
        return firstPage(client, overlayHeaders, path, baseQuery, request, decoder, true);
    }

    private static <T> GitHubPage<T> firstPage(
            final GitHubClient client,
            final HttpHeaders overlayHeaders,
            final String path,
            final Map<String, List<String>> baseQuery,
            final GitHubPageRequest request,
            final GitHubPageDecoder<T> decoder,
            final boolean sendPerPage)
            throws IOException {
        return GitHubPagers.start(
                client,
                client.pagination(),
                new GitHubPageTarget(path, baseQuery, overlayHeaders),
                request,
                GitHubListSpec.<T>builder()
                        .allowedKinds(kindsFor(request))
                        .decoder(decoder == null ? null : unused -> decoder)
                        .sendPerPage(sendPerPage)
                        .build());
    }

    private static Set<GitHubPageRequestKind> kindsFor(final GitHubPageRequest request) {
        if (request == null) {
            return GitHubPageRequestKindValidators.PAGE;
        }
        return switch (GitHubPageRequestKind.of(request)) {
            case NONE, PAGE -> GitHubPageRequestKindValidators.PAGE;
            case SINCE -> GitHubPageRequestKindValidators.SINCE;
            case CURSOR -> GitHubPageRequestKindValidators.CURSOR;
            case DELIVERY_CURSOR -> GitHubPageRequestKindValidators.DELIVERY_CURSOR;
            case START_INDEX -> GitHubPageRequestKindValidators.START_INDEX;
        };
    }

    private static void loadWithNull(final GitHubClient client, final LoadNullArg argument) throws IOException {
        switch (argument) {
            case CLIENT ->
                firstPage(
                        null,
                        HttpHeaders.empty(),
                        "/users",
                        Map.of(),
                        FIRST_SINCE,
                        unused -> GitHubPageBody.of(List.of()));
            case OVERLAY_HEADERS ->
                firstPage(client, null, "/users", Map.of(), FIRST_SINCE, unused -> GitHubPageBody.of(List.of()));
            case PATH ->
                firstPage(
                        client,
                        HttpHeaders.empty(),
                        null,
                        Map.of(),
                        FIRST_SINCE,
                        unused -> GitHubPageBody.of(List.of()));
            case BASE_QUERY ->
                firstPage(
                        client,
                        HttpHeaders.empty(),
                        "/users",
                        null,
                        FIRST_SINCE,
                        unused -> GitHubPageBody.of(List.of()));
            case REQUEST ->
                firstPage(
                        client, HttpHeaders.empty(), "/users", Map.of(), null, unused -> GitHubPageBody.of(List.of()));
            case DECODER -> firstPage(client, HttpHeaders.empty(), "/users", Map.of(), FIRST_SINCE, null);
        }
    }

    private void stubThreeFullPages() throws IOException {
        when(transport.execute(any()))
                .thenReturn(
                        jsonResponseWithLink(
                                "users-one-two.json", "<https://api.github.com/repos?page=2&per_page=2>; rel=\"next\""),
                        jsonResponseWithLink(
                                "users-one-two.json", "<https://api.github.com/repos?page=3&per_page=2>; rel=\"next\""),
                        jsonResponse("users-one-two.json"));
    }

    private GitHubPage<TestUser> loadThreePageWalk(final GitHubClient client) throws IOException {
        return load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
    }

    private URI captureFirstUri() throws Exception {
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, atLeastOnce()).execute(captor.capture());
        return captor.getAllValues().getFirst().uri();
    }

    private ThreePageWalk walkThreePages() throws Exception {
        final Optional<GitHubPage<TestUser>> third;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            final GitHubPage<TestUser> second = first.next().orElseThrow();
            third = second.next();
        }
        return new ThreePageWalk(captureUris(3), third);
    }

    private record ThreePageWalk(List<URI> uris, Optional<GitHubPage<TestUser>> third) {}

    private void assertPaginationLimitExceededAfterTwoPages(final ThrowingConsumer<GitHubPage<TestUser>> action)
            throws Exception {
        final PaginationConfiguration pagination =
                PaginationConfiguration.builder().maxPages(2).build();
        final HttpResponse firstPage =
                jsonResponseWithLink("users-one.json", "<https://api.github.com/users?page=2>; rel=\"next\"");
        final HttpResponse secondPage =
                jsonResponseWithLink("users-two.json", "<https://api.github.com/users?page=3>; rel=\"next\"");
        when(transport.execute(any())).thenReturn(firstPage, secondPage);

        final Throwable thrown;
        try (final GitHubClient client =
                TestGitHubClients.raw(transport, TestClients.configuration(GITHUB_BASE), pagination)) {
            final GitHubPage<TestUser> first = load(client, "/users", GitHubSinceRequest.first(1));
            thrown = catchThrowable(() -> action.accept(first));
        }

        assertThat(thrown).isInstanceOf(GitHubPaginationLimitExceededException.class);
        verify(transport, times(2)).execute(any());
    }

    private List<URI> captureUris(final int times) throws Exception {
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(times)).execute(captor.capture());
        return captor.getAllValues().stream().map(HttpRequest::uri).toList();
    }

    private URI captureUri() throws Exception {
        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, atLeastOnce()).execute(captor.capture());
        return captor.getAllValues().getLast().uri();
    }

    private static HttpResponse usersResponse(final String... logins) {
        return HttpResponses.of(HttpStatus.OK.code(), Map.of(), HttpBody.of(usersBody(logins)));
    }

    private static byte[] usersBody(final String... logins) {
        final List<String> users = new ArrayList<>();
        for (int i = 0; i < logins.length; i++) {
            users.add(userJson(logins[i], i + 1));
        }
        return ("[" + String.join(",", users) + "]").getBytes(StandardCharsets.UTF_8);
    }

    private static String userJson(final String login, final int id) {
        return """
                {"login":"%1$s","id":%2$d,"node_id":"n","avatar_url":"https://example.com/a","gravatar_id":"",\
                "url":"%3$s","html_url":"https://github.com/%1$s","followers_url":"%3$s/followers",\
                "following_url":"%3$s/following{/other_user}","gists_url":"%3$s/gists{/gist_id}",\
                "starred_url":"%3$s/starred{/owner}{/repo}","subscriptions_url":"%3$s/subscriptions",\
                "organizations_url":"%3$s/orgs","repos_url":"%3$s/repos","events_url":"%3$s/events{/privacy}",\
                "received_events_url":"%3$s/received_events","type":"User","site_admin":false}""".formatted(login, id, "https://api.github.com/users/" + login);
    }

    private static HttpResponse jsonResponse(final String fileName) {
        return HttpResponses.of(
                HttpStatus.OK.code(), Map.of(), HttpBody.of(FileUtils.readBytes(GitHubPagerWalkTest.class, fileName)));
    }

    private static HttpResponse jsonResponseWithLink(final String fileName, final String link) {
        return HttpResponses.of(
                HttpStatus.OK.code(),
                Map.of(LINK, List.of(link)),
                HttpBody.of(FileUtils.readBytes(GitHubPagerWalkTest.class, fileName)));
    }

    enum LoadNullArg {
        CLIENT("client"),
        OVERLAY_HEADERS("overlayHeaders"),
        PATH("path"),
        BASE_QUERY("query"),
        REQUEST("request"),
        DECODER("decoder");

        private final String parameterName;

        LoadNullArg(final String parameterName) {
            this.parameterName = parameterName;
        }

        String parameterName() {
            return parameterName;
        }
    }

    private GitHubPage<String> loadSearch(final GitHubClient client, final GitHubPageRequest request)
            throws IOException {
        return GitHubPagers.startSearch(
                client,
                client.pagination(),
                new GitHubPageTarget("/search/topics", TOPICS_QUERY, HttpHeaders.empty()),
                request,
                searchDecoder());
    }

    private static GitHubSearchPageDecoder<String> searchDecoder() {
        return response -> {
            final JsonNode tree = JSON_MAPPER.readTree(response.body().mapBytes(bytes -> bytes, () -> new byte[0]));
            final List<String> items = new ArrayList<>();
            for (final JsonNode item : tree.get("items")) {
                items.add(item.get("name").asString());
            }
            final JsonNode reasons = tree.get("lexical_fallback_reason");
            final List<GitHubLexicalFallbackReason> lexicalFallbackReasons;
            if (reasons == null || reasons.isNull()) {
                lexicalFallbackReasons = null;
            } else {
                lexicalFallbackReasons = new ArrayList<>();
                for (final JsonNode reason : reasons) {
                    lexicalFallbackReasons.add(GitHubLexicalFallbackReason.fromWire(reason.asString()));
                }
            }
            return new GitHubSearchEnvelope<>(
                    tree.get("total_count").asInt(),
                    tree.get("incomplete_results").asBoolean(),
                    items,
                    tree.has("search_type")
                            ? GitHubIssueSearchType.fromWire(
                                    tree.get("search_type").asString())
                            : null,
                    lexicalFallbackReasons);
        };
    }

    private static HttpResponse searchItems(final int totalCount, final int itemCount) {
        return searchItems(totalCount, itemCount, "i");
    }

    private static HttpResponse searchItems(final int totalCount, final int itemCount, final int page) {
        final HttpResponse response = searchItems(totalCount, itemCount, "p" + page + "i");
        return withLink(
                response, "<https://api.github.com/search/topics?q=ruby&page=" + (page + 1) + ">; rel=\"next\"");
    }

    private static HttpResponse withLink(final HttpResponse response, final String link) {
        return new HttpResponse(response.statusCode(), HttpHeaders.of(LINK, link), response.body());
    }

    private static HttpResponse searchItems(final int totalCount, final int itemCount, final String namePrefix) {
        final StringBuilder items = new StringBuilder();
        for (int i = 0; i < itemCount; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"name\":\"").append(namePrefix).append(i).append("\"}");
        }
        return ok("{\"total_count\":" + totalCount + ",\"incomplete_results\":false,\"items\":[" + items + "]}");
    }

    private void stubGithubDefaultSearch(final int totalCount) throws Exception {
        when(transport.execute(any()))
                .thenAnswer(invocation -> githubDefaultSearchResponse(
                        invocation.getArgument(0, HttpRequest.class).uri(), totalCount));
    }

    private static HttpResponse githubDefaultSearchResponse(final URI uri, final int totalCount) {
        final Map<String, String> query = queryMap(uri);
        final int page = Integer.parseInt(query.getOrDefault("page", "1"));
        final int perPage = Integer.parseInt(query.getOrDefault("per_page", "30"));
        final int reachable = Math.min(totalCount, GitHubPage.SEARCH_RESULT_LIMIT);
        final int from = (page - 1) * perPage;
        final int to = Math.min(reachable, page * perPage);
        final String json = "{\"total_count\":"
                + totalCount
                + ",\"incomplete_results\":false,\"items\":"
                + searchItemArray(Math.max(0, to - from))
                + "}";
        if (to >= reachable) {
            return ok(json);
        }
        final Map<String, String> nextQuery = new LinkedHashMap<>(query);
        nextQuery.put("page", String.valueOf(page + 1));
        final StringBuilder next = new StringBuilder();
        nextQuery.forEach((name, value) -> {
            if (!next.isEmpty()) {
                next.append('&');
            }
            next.append(name).append('=').append(value);
        });
        return searchJsonWithLink(json, "<https://api.github.com/search/topics?" + next + ">; rel=\"next\"");
    }

    private static Map<String, String> queryMap(final URI uri) {
        final Map<String, String> query = new LinkedHashMap<>();
        final String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        for (final String part : raw.split("&")) {
            final int eq = part.indexOf('=');
            if (eq < 0) {
                query.put(part, "");
            } else {
                query.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return query;
    }

    private static Stream<Arguments> noneSearchTotals() {
        return Stream.of(Arguments.of(80, 80), Arguments.of(250, 250), Arguments.of(5000, 1000));
    }

    private static int pageFromUri(final URI uri) {
        final String rawQuery = uri.getQuery();
        final String query = rawQuery == null ? "" : rawQuery;
        for (final String part : query.split("&")) {
            if (part.startsWith("page=")) {
                return Integer.parseInt(part.substring("page=".length()));
            }
        }
        return 1;
    }

    private static HttpResponse searchJsonWithLink(final String json, final String link) {
        return new HttpResponse(
                HttpStatus.OK.code(), HttpHeaders.of(LINK, link), HttpBody.of(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static String searchItemArray(final int itemCount) {
        final StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < itemCount; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"name\":\"i").append(i).append("\"}");
        }
        return items.append(']').toString();
    }

    private static Stream<Arguments> withoutPerPageFirstQueries() {
        return Stream.of(
                Arguments.of(GitHubPageRequest.byPage(), "/users", "https://api.github.com/users?page=1"),
                Arguments.of(GitHubSinceRequest.of(9L), "/users", "https://api.github.com/users?since=9"),
                Arguments.of(
                        GitHubCursorRequest.after("a"), "/attestations", "https://api.github.com/attestations?after=a"),
                Arguments.of(
                        GitHubCursorRequest.before("b"),
                        "/attestations",
                        "https://api.github.com/attestations?before=b"),
                Arguments.of(
                        GitHubDeliveryCursorRequest.of("abc"),
                        "/deliveries",
                        "https://api.github.com/deliveries?cursor=abc"),
                Arguments.of(
                        GitHubStartIndexRequest.of(3, 2), "/scim", "https://api.github.com/scim?startIndex=3&count=2"),
                Arguments.of(GitHubPageRequest.none(), "/users", "https://api.github.com/users"));
    }

    @Test
    void loadNext_shouldWalkThreePagesFromBody_whenStartIndexRequest() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        ok("{\"names\":[\"a\",\"b\"],\"total_results\":5}"),
                        ok("{\"names\":[\"c\",\"d\"],\"total_results\":5}"),
                        ok("{\"names\":[\"e\"],\"total_results\":5}"));

        final GitHubPage<String> first;
        final GitHubPage<String> second;
        final GitHubPage<String> third;
        final Optional<GitHubPage<String>> fourth;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = startIndexNames(client, GitHubStartIndexRequest.of(1, 2));
            second = first.next().orElseThrow();
            third = second.next().orElseThrow();
            fourth = third.next();
        }

        assertSoftly(softly -> {
            softly.assertThat(first.items()).containsExactly("a", "b");
            softly.assertThat(second.items()).containsExactly("c", "d");
            softly.assertThat(third.items()).containsExactly("e");
            softly.assertThat(fourth).isEmpty();
        });
        assertThat(captureUris(3))
                .extracting(URI::toString)
                .containsExactly(
                        "https://api.github.com/scim?startIndex=1&count=2",
                        "https://api.github.com/scim?startIndex=3&count=2",
                        "https://api.github.com/scim?startIndex=5&count=2");
    }

    @Test
    void loadNext_shouldIgnoreLinkHeader_whenStartIndexRequest() throws Exception {
        final String foreignLink = "<https://evil.example/scim?startIndex=99>; rel=\"next\"";
        when(transport.execute(any()))
                .thenReturn(
                        withLink(ok("{\"names\":[\"a\",\"b\"],\"total_results\":4}"), foreignLink),
                        ok("{\"names\":[\"c\",\"d\"],\"total_results\":4}"));

        final GitHubPage<String> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            second = startIndexNames(client, GitHubStartIndexRequest.of(1, 2))
                    .next()
                    .orElseThrow();
        }

        assertThat(second.items()).containsExactly("c", "d");
        assertThat(captureUris(2))
                .extracting(URI::toString)
                .containsExactly(
                        "https://api.github.com/scim?startIndex=1&count=2",
                        "https://api.github.com/scim?startIndex=3&count=2");
    }

    @Test
    void loadNext_shouldEndWalk_whenStartIndexBodyHasNoMoreResultsDespiteLinkHeader() throws Exception {
        final String link = "<https://api.github.com/scim?startIndex=3>; rel=\"next\"";
        when(transport.execute(any())).thenReturn(withLink(ok("{\"names\":[\"a\",\"b\"],\"total_results\":2}"), link));

        final Optional<GitHubPage<String>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            second = startIndexNames(client, GitHubStartIndexRequest.of(1, 2)).next();
        }

        assertThat(second).isEmpty();
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldThrow_whenMaxPagesReachedWithStartIndexRequest() throws Exception {
        final PaginationConfiguration pagination =
                PaginationConfiguration.builder().maxPages(1).build();
        when(transport.execute(any())).thenReturn(ok("{\"names\":[\"a\",\"b\"],\"total_results\":5}"));

        final Throwable thrown;
        try (final GitHubClient client =
                TestGitHubClients.raw(transport, TestClients.configuration(GITHUB_BASE), pagination)) {
            final GitHubPage<String> first = startIndexNames(client, GitHubStartIndexRequest.of(1, 2));
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationLimitExceededException.class)
                .extracting(
                        ex -> ((GitHubPaginationLimitExceededException) ex).maxPages(),
                        ex -> ((GitHubPaginationLimitExceededException) ex).pageCount())
                .containsExactly(1, 1);
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldSendOverlayHeaders_whenStartIndexWalkReachesLaterPage() throws Exception {
        final HttpHeaders overlay = HttpHeaders.of("Accept", "application/scim+json");
        when(transport.execute(any()))
                .thenReturn(
                        ok("{\"names\":[\"a\",\"b\"],\"total_results\":3}"),
                        ok("{\"names\":[\"c\"],\"total_results\":3}"));

        try (final GitHubClient client = TestClients.client(transport)) {
            startIndexNames(client, GitHubStartIndexRequest.of(1, 2), overlay)
                    .next()
                    .orElseThrow();
        }

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(request -> request.headers().get("Accept").orElseThrow())
                .containsExactly("application/scim+json", "application/scim+json");
    }

    @Test
    void loadNext_shouldContinueFromReceivedItems_whenPageHasFewerItemsThanCount() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        ok("{\"names\":[\"a\",\"b\"],\"total_results\":5}"),
                        ok("{\"names\":[\"c\"],\"total_results\":5}"));

        try (final GitHubClient client = TestClients.client(transport)) {
            startIndexNames(client, GitHubStartIndexRequest.of(1, 3)).next().orElseThrow();
        }

        assertThat(captureUris(2))
                .extracting(URI::toString)
                .containsExactly(
                        "https://api.github.com/scim?startIndex=1&count=3",
                        "https://api.github.com/scim?startIndex=3&count=3");
    }

    @Test
    void loadNext_shouldEndWalk_whenLaterStartIndexPageIsEmptyWithStableTotal() throws Exception {
        when(transport.execute(any()))
                .thenReturn(
                        ok("{\"names\":[\"a\",\"b\"],\"total_results\":5}"), ok("{\"names\":[],\"total_results\":5}"));

        final Optional<GitHubPage<String>> third;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<String> second = startIndexNames(client, GitHubStartIndexRequest.of(1, 2))
                    .next()
                    .orElseThrow();
            third = second.next();
        }

        assertThat(third).isEmpty();
        verify(transport, times(2)).execute(any());
    }

    @Test
    void start_shouldSendFirstIndex_whenNoneTokenGivenToStartIndexSpec() throws Exception {
        when(transport.execute(any())).thenReturn(ok("{\"names\":[\"a\"],\"total_results\":1}"));

        final GitHubPage<String> first;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = startIndexNames(client, GitHubPageRequest.none());
        }

        assertThat(first.items()).containsExactly("a");
        assertThat(captureUri()).hasToString("https://api.github.com/scim?startIndex=1");
    }

    @Test
    void start_shouldThrow_whenPageNumberTokenGivenToStartIndexSpec() throws Exception {
        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final PaginationConfiguration configured = client.pagination();
            final GitHubListSpec<String> spec = scimNamesSpec();
            final GitHubPageRequest request = GitHubPageNumberRequest.of(1, 2);

            thrown = catchThrowable(() -> GitHubPagers.start(client, configured, target("/scim"), request, spec));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        verify(transport, never()).execute(any());
    }

    @Test
    void start_shouldThrow_whenStartIndexTokenGivenToPageNumberSpec() throws Exception {
        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final PaginationConfiguration configured = client.pagination();
            final GitHubListSpec<String> spec = namesSpec();
            final GitHubPageRequest request = GitHubStartIndexRequest.of(1, 2);

            thrown = catchThrowable(() -> GitHubPagers.start(client, configured, target("/repos"), request, spec));
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        verify(transport, never()).execute(any());
    }

    @Test
    void loadNext_shouldReturnEmpty_whenStartIndexFirstPageEmpty() throws Exception {
        when(transport.execute(any())).thenReturn(ok("{\"names\":[],\"total_results\":5}"));

        final GitHubPage<String> first;
        final Optional<GitHubPage<String>> second;
        try (final GitHubClient client = TestClients.client(transport)) {
            first = startIndexNames(client, GitHubStartIndexRequest.first());
            second = first.next();
        }

        assertSoftly(softly -> {
            softly.assertThat(first.items()).isEmpty();
            softly.assertThat(first.totalCount()).isEqualTo(5);
            softly.assertThat(second).isEmpty();
        });
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldThrowLoop_whenNextLinkPointsBackAtFirstRequest() throws Exception {
        final String link = "<https://api.github.com/users?per_page=2>; rel=\"next\"";
        when(transport.execute(any())).thenReturn(jsonResponseWithLink("users-one-two.json", link));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/users", FIRST_SINCE);
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationLoopException.class)
                .hasMessageContaining("repeated after 1 page(s)");
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldThrowLoop_whenNextLinkPointsBackAtFirstPageNumberRequest() throws Exception {
        final String link = "<https://api.github.com/repos?page=1&per_page=2>; rel=\"next\"";
        when(transport.execute(any())).thenReturn(jsonResponseWithLink("users-one-two.json", link));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(transport)) {
            final GitHubPage<TestUser> first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown).isInstanceOf(GitHubPaginationLoopException.class);
        verify(transport, times(1)).execute(any());
    }

    @Test
    void loadNext_shouldNotHint_whenTheLinkIsOpaque() throws Exception {
        when(transport.execute(any()))
                .thenReturn(jsonResponseWithLink("users-one-two.json", "<urn:example:next>; rel=\"next\""));

        final Throwable thrown;
        try (final GitHubClient client = TestClients.client(TestHosts.GHES, transport)) {
            final GitHubPage<TestUser> first = load(client, "/repos", GitHubPageNumberRequest.of(1, 2));
            thrown = catchThrowable(first::next);
        }

        assertThat(thrown)
                .isInstanceOf(GitHubPaginationException.class)
                .hasMessageContaining("outside the API base URI")
                .hasMessageNotContaining("configured hostname");
    }

    @Test
    void first_shouldCarryTheTaskCounts_whenTheDecoderReturnsThem() throws Exception {
        when(transport.execute(any())).thenReturn(jsonResponse("users-one-two.json"));

        final GitHubPage<TestUser> page;
        try (final GitHubClient client = TestClients.client(TestHosts.GHES, transport)) {
            page = firstPage(
                    client,
                    HttpHeaders.empty(),
                    "/repos",
                    Map.of(),
                    GitHubPageNumberRequest.of(1, 2),
                    response -> GitHubPageBody.of(List.of(TestUser.OCTOCAT), new GitHubTaskCounts(2, 1)));
        }

        assertSoftly(softly -> {
            softly.assertThat(page.totalActiveCount()).isEqualTo(2);
            softly.assertThat(page.totalArchivedCount()).isEqualTo(1);
        });
    }
}
