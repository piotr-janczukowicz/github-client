package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_LIMIT;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_USED;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitSnapshot;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.testsupport.MutableClock;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@UnitTest
@Isolated
class GitHubRateLimitTrackerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long WINDOW = 1_767_225_600L;

    @Test
    void observe_shouldKeepLatestReceived_whenSnapshotsOfOneWindowArrive() {
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(null, CLOCK);

        tracker.observe(headers("core", 90, WINDOW));
        tracker.observe(headers("core", 95, WINDOW));
        tracker.observe(headers("core", 80, WINDOW));

        assertThat(tracker.last("core").orElseThrow().remaining()).isEqualTo(80);
    }

    @Test
    void observe_shouldAcceptHigherRemaining_whenSameWindow() {
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(null, CLOCK);

        tracker.observe(headers("core", 10, WINDOW));
        tracker.observe(headers("core", 50, WINDOW));

        assertThat(tracker.last("core").orElseThrow().remaining()).isEqualTo(50);
    }

    @Test
    void observe_shouldIgnoreEarlierWindow_whenItArrivesAfterALaterOne() {
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(null, CLOCK);

        tracker.observe(headers("core", 5, WINDOW + 3600));
        tracker.observe(headers("core", 4000, WINDOW));

        assertSoftly(softly -> {
            softly.assertThat(tracker.last("core").orElseThrow().remaining()).isEqualTo(5);
            softly.assertThat(tracker.last("core").orElseThrow().resetAt())
                    .isEqualTo(Instant.ofEpochSecond(WINDOW + 3600));
        });
    }

    @Test
    void observe_shouldKeepUnknownResourceApart_whenResourceHeaderIsMissing() {
        final List<GitHubRateLimitSnapshot> seen = new ArrayList<>();
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(seen::add, CLOCK);
        tracker.observe(headers("core", 90, WINDOW));

        tracker.observe(HttpHeaders.of(
                RATE_LIMIT_LIMIT, "100", RATE_LIMIT_REMAINING, "7", RATE_LIMIT_RESET, String.valueOf(WINDOW)));

        assertSoftly(softly -> {
            softly.assertThat(tracker.last("core").orElseThrow().remaining()).isEqualTo(90);
            softly.assertThat(tracker.last(GitHubRateLimitSnapshot.UNKNOWN_RESOURCE)
                            .orElseThrow()
                            .remaining())
                    .isEqualTo(7);
            softly.assertThat(seen)
                    .last()
                    .satisfies(
                            snapshot -> softly.assertThat(snapshot.resource()).isEqualTo("unknown"));
        });
    }

    @Test
    void observe_shouldReplaceOlderWindow_whenResetIsLater() {
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(null, CLOCK);

        tracker.observe(headers("core", 3, WINDOW));
        tracker.observe(headers("core", 100, WINDOW + 3600));
        tracker.observe(headers("core", 1, WINDOW));

        assertThat(tracker.last("core").orElseThrow().remaining()).isEqualTo(100);
    }

    @Test
    void observe_shouldTrackResourcesSeparately() {
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(null, CLOCK);

        tracker.observe(headers("core", 90, WINDOW));
        tracker.observe(headers("search", 20, WINDOW));

        assertSoftly(softly -> {
            softly.assertThat(tracker.all()).containsOnlyKeys("core", "search");
            softly.assertThat(tracker.last("search").orElseThrow().remaining()).isEqualTo(20);
            softly.assertThat(tracker.last("graphql")).isEmpty();
        });
    }

    @Test
    void observe_shouldIgnoreResponse_whenQuotaMissing() {
        final List<GitHubRateLimitSnapshot> seen = new ArrayList<>();
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(seen::add, CLOCK);

        tracker.observe(HttpHeaders.of(CONTENT_TYPE, "application/json"));

        assertSoftly(softly -> {
            softly.assertThat(tracker.all()).isEmpty();
            softly.assertThat(seen).isEmpty();
        });
    }

    @Test
    void observe_shouldReportEverySnapshotToListener_whenTrackerDoesNotKeepIt() {
        final List<GitHubRateLimitSnapshot> seen = new ArrayList<>();
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(seen::add, CLOCK);

        tracker.observe(headers("core", 80, WINDOW));
        tracker.observe(headers("core", 95, WINDOW));
        tracker.observe(headers("core", 95, WINDOW));
        tracker.observe(headers("core", 4000, WINDOW - 3600));

        assertSoftly(softly -> {
            softly.assertThat(seen)
                    .extracting(GitHubRateLimitSnapshot::remaining)
                    .containsExactly(80, 95, 95, 4000);
            softly.assertThat(seen.getFirst().observedAt()).isEqualTo(NOW);
            softly.assertThat(tracker.last("core").orElseThrow().remaining()).isEqualTo(95);
        });
    }

    @Test
    void observe_shouldKeepFirstObservation_whenQuotaIsIdentical() {
        final Instant later = NOW.plusSeconds(5);
        final MutableClock clock = new MutableClock(NOW);
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(null, clock);

        tracker.observe(headers("core", 80, WINDOW));
        clock.set(later);
        tracker.observe(headers("core", 80, WINDOW));

        assertThat(tracker.last("core").orElseThrow().observedAt()).isEqualTo(NOW);
    }

    @Test
    void observe_shouldReplaceSnapshot_whenLimitDiffersAtEqualRemaining() {
        final MutableClock clock = new MutableClock(NOW);
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(null, clock);

        tracker.observe(headers("core", 80, WINDOW));
        clock.set(NOW.plusSeconds(5));
        tracker.observe(HttpHeaders.of(
                RATE_LIMIT_LIMIT,
                "200",
                RATE_LIMIT_REMAINING,
                "80",
                RATE_LIMIT_RESET,
                String.valueOf(WINDOW),
                RATE_LIMIT_RESOURCE,
                "core"));

        final GitHubRateLimitSnapshot last = tracker.last("core").orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(last.limit()).isEqualTo(200);
            softly.assertThat(last.observedAt()).isEqualTo(NOW.plusSeconds(5));
        });
    }

    @Test
    void observe_shouldReplaceSnapshot_whenUsedDiffersAtEqualRemaining() {
        final MutableClock clock = new MutableClock(NOW);
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(null, clock);

        tracker.observe(headers("core", 80, WINDOW));
        clock.set(NOW.plusSeconds(5));
        tracker.observe(HttpHeaders.of(
                RATE_LIMIT_LIMIT,
                "100",
                RATE_LIMIT_REMAINING,
                "80",
                RATE_LIMIT_USED,
                "7",
                RATE_LIMIT_RESET,
                String.valueOf(WINDOW),
                RATE_LIMIT_RESOURCE,
                "core"));

        assertThat(tracker.last("core").orElseThrow().used()).isEqualTo(7);
    }

    @Test
    void observe_shouldNotifyListener_whenSnapshotIsNotStored() {
        final List<GitHubRateLimitSnapshot> seen = new ArrayList<>();
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(seen::add, CLOCK);

        tracker.observe(headers("core", 80, WINDOW));
        tracker.observe(headers("core", 80, WINDOW));

        assertThat(seen).hasSize(2);
    }

    @Test
    void observe_shouldNotFail_whenListenerThrows() {
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(
                snapshot -> {
                    throw new IllegalStateException("listener broke");
                },
                CLOCK);

        tracker.observe(headers("core", 80, WINDOW));

        assertThat(tracker.last("core")).isPresent();
    }

    @Test
    void observe_shouldStoreAnObservedValue_whenThreadsObserveOneWindowConcurrently() throws Exception {
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(null, CLOCK);
        final List<Integer> remainings = new ArrayList<>();
        for (int remaining = 1; remaining <= 400; remaining++) {
            remainings.add(remaining);
        }
        Collections.shuffle(remainings, new Random(42));

        observeConcurrently(tracker, remainings, remaining -> headers("core", remaining, WINDOW));

        assertSoftly(softly -> {
            softly.assertThat(tracker.all()).containsOnlyKeys("core");
            softly.assertThat(tracker.last("core").orElseThrow().remaining()).isBetween(1, 400);
        });
    }

    @Test
    void observe_shouldKeepLaterWindow_whenThreadsObserveTwoWindowsConcurrently() throws Exception {
        final GitHubRateLimitTracker tracker = new GitHubRateLimitTracker(null, CLOCK);
        final List<Integer> remainings = new ArrayList<>();
        for (int remaining = 1; remaining <= 400; remaining++) {
            remainings.add(remaining);
        }
        final long laterWindowReset = WINDOW + 3600;
        Collections.shuffle(remainings, new Random(7));

        observeConcurrently(
                tracker,
                remainings,
                remaining -> headers("core", remaining, remaining % 2 == 0 ? WINDOW : laterWindowReset));

        final GitHubRateLimitSnapshot last = tracker.last("core").orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(last.resetAt()).isEqualTo(Instant.ofEpochSecond(laterWindowReset));
            softly.assertThat(last.remaining() % 2).isEqualTo(1);
        });
    }

    private static void observeConcurrently(
            final GitHubRateLimitTracker tracker,
            final List<Integer> remainings,
            final Function<Integer, HttpHeaders> headersFor)
            throws Exception {
        final int threads = 16;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                final int offset = thread;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = offset; i < remainings.size(); i += threads) {
                        tracker.observe(headersFor.apply(remainings.get(i)));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (final Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static HttpHeaders headers(final String resource, final int remaining, final long reset) {
        return HttpHeaders.of(
                RATE_LIMIT_LIMIT,
                "100",
                RATE_LIMIT_REMAINING,
                String.valueOf(remaining),
                RATE_LIMIT_RESET,
                String.valueOf(reset),
                RATE_LIMIT_RESOURCE,
                resource);
    }
}
