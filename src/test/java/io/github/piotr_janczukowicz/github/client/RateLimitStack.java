package io.github.piotr_janczukowicz.github.client;

import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitDetector;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitListener;
import io.github.piotr_janczukowicz.http.client.authentication.AuthenticationProvider;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.RateLimitEvent;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.http.transport.RetryingHttpTransport;
import io.github.piotr_janczukowicz.test.SwitchableAuthenticationProvider;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.TestRetryingTransports;
import io.github.piotr_janczukowicz.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

final class RateLimitStack implements AutoCloseable {

    private final Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final MutableClock clock = new MutableClock(start);
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private final List<RateLimitEvent> events = new CopyOnWriteArrayList<>();
    private final RetryingHttpTransport transport;
    private final SwitchableAuthenticationProvider credential =
            new SwitchableAuthenticationProvider(TestClients.auth());
    private final GitHubClient client;

    RateLimitStack(final HttpTransport delegate) {
        this(delegate, builder -> builder);
    }

    RateLimitStack(final HttpTransport delegate, final UnaryOperator<RetryConfiguration.Builder> customize) {
        this(delegate, customize, null, null);
    }

    RateLimitStack(
            final HttpTransport delegate,
            final UnaryOperator<RetryConfiguration.Builder> customize,
            final @Nullable RandomGenerator random,
            final TestRetryingTransports.@Nullable Pause pause) {
        this(delegate, customize, random, pause, null);
    }

    private RateLimitStack(
            final HttpTransport delegate,
            final UnaryOperator<RetryConfiguration.Builder> customize,
            final @Nullable RandomGenerator random,
            final TestRetryingTransports.@Nullable Pause pause,
            final @Nullable GitHubRateLimitListener listener) {
        final RetryConfiguration retry = customize
                .apply(RetryConfiguration.builder()
                        .rateLimitJitter(Duration.ZERO)
                        .rateLimitEventListener(events::add))
                .build()
                .withRateLimitDetector(GitHubRateLimitDetector.INSTANCE);
        final TestRetryingTransports.Pause waits =
                pause == null ? TestRetryingTransports.recordingAndAdvancing(sleeps, clock) : pause;
        this.transport = random == null
                ? TestRetryingTransports.create(delegate, retry, waits, clock)
                : TestRetryingTransports.create(delegate, retry, waits, clock, random);
        final DefaultGitHubClient.Builder builder = DefaultGitHubClient.builder(
                        transport, TestClients.configuration(TestClients.GITHUB_BASE), credential)
                .userAgent(TestClients.USER_AGENT);
        this.client = (listener == null ? builder : builder.rateLimitListener(listener)).build();
    }

    static RateLimitStack listening(final HttpTransport delegate, final GitHubRateLimitListener listener) {
        return new RateLimitStack(delegate, builder -> builder, null, null, listener);
    }

    Instant start() {
        return start;
    }

    MutableClock clock() {
        return clock;
    }

    List<Duration> sleeps() {
        return sleeps;
    }

    List<RateLimitEvent> events() {
        return events;
    }

    GitHubClient client() {
        return client;
    }

    void use(final AuthenticationProvider auth) {
        credential.use(auth);
    }

    int gateSize() {
        return TestRetryingTransports.gateSize(transport);
    }

    @Override
    public void close() {
        client.close();
        transport.close();
    }
}
