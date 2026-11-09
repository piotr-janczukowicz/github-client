package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.http.HttpHeaderNames.CONTENT_TYPE;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.RETRY_AFTER;
import static io.github.piotr_janczukowicz.http.HttpMethod.GET;
import static io.github.piotr_janczukowicz.http.HttpStatus.NOT_FOUND;
import static io.github.piotr_janczukowicz.http.HttpStatus.OK;
import static io.github.piotr_janczukowicz.http.HttpStatus.TOO_MANY_REQUESTS;
import static io.github.piotr_janczukowicz.test.http.StubHttpServer.status;
import static io.github.piotr_janczukowicz.test.support.GitHubClientWireMockSupport.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.piotr_janczukowicz.github.client.exception.GitHubNotFoundException;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.http.client.HttpClientConfiguration;
import io.github.piotr_janczukowicz.http.transport.HttpTransport;
import io.github.piotr_janczukowicz.http.transport.HttpTransports;
import io.github.piotr_janczukowicz.http.transport.RetryConfiguration;
import io.github.piotr_janczukowicz.http.transport.RetryingHttpTransport;
import io.github.piotr_janczukowicz.test.TestClients;
import io.github.piotr_janczukowicz.test.http.StubHttpServer;
import io.github.piotr_janczukowicz.test.support.GitHubClientWireMockSupport;
import io.github.piotr_janczukowicz.testsupport.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

@IntegrationTest
@Isolated
class GitHubClientConcurrencyIntegrationTest {

    private static final int THREADS = 8;
    private static final int PATHS = 10;
    private static final int ROUNDS = 5;

    @RegisterExtension
    static final StubHttpServer HTTP = new StubHttpServer();

    record Thing(String name) {}

    @Test
    void fetch_shouldReturnEachCallersOwnResponse_whenClientIsSharedByManyThreads() throws Exception {
        for (int i = 0; i < PATHS; i++) {
            HTTP.stub(
                    GET,
                    "/thing/" + i,
                    status(OK)
                            .header(CONTENT_TYPE, "application/json")
                            .body(("{\"name\":\"n" + i + "\"}").getBytes(StandardCharsets.UTF_8)));
        }
        HTTP.stub(GET, "/missing", status(NOT_FOUND));

        final List<String> failures;
        try (final GitHubClientWireMockSupport.WireMockClient client = client(HTTP)) {
            failures = runConcurrently(THREADS, thread -> () -> {
                final List<String> problems = new ArrayList<>();
                final GitHubCaller caller = client.caller();
                final HttpCall missing = HttpCall.get("/missing");
                for (int round = 0; round < ROUNDS; round++) {
                    for (int i = 0; i < PATHS; i++) {
                        final int index = (i + thread) % PATHS;
                        final Thing thing = caller.fetch(HttpCall.get("/thing/" + index), Thing.class);
                        if (!thing.equals(new Thing("n" + index))) {
                            problems.add("thread " + thread + " asked /thing/" + index + " and got " + thing);
                        }
                    }
                    final Throwable thrown = catchThrowable(() -> caller.send(missing));
                    if (!(thrown instanceof GitHubNotFoundException)) {
                        problems.add("thread " + thread + " expected not found for /missing but got " + thrown);
                    }
                }
                return problems;
            });
        }

        assertThat(failures).isEmpty();
    }

    @Test
    void send_shouldRetryEachCallerIndependently_whenRateLimitedConcurrently() throws Exception {
        for (int thread = 0; thread < THREADS; thread++) {
            HTTP.stub(
                    GET,
                    "/flaky/" + thread,
                    status(TOO_MANY_REQUESTS).header(RETRY_AFTER, "0"),
                    status(OK)
                            .header(CONTENT_TYPE, "application/json")
                            .body(("{\"name\":\"t" + thread + "\"}").getBytes(StandardCharsets.UTF_8)));
        }

        final RetryConfiguration retry = RetryConfiguration.builder()
                .rateLimitJitter(Duration.ZERO)
                .clockSkewMargin(Duration.ZERO)
                .minRateLimitWait(Duration.ZERO)
                .build();
        final HttpClientConfiguration config = TestClients.configuration(HTTP.baseUri());

        final List<String> failures;
        try (final HttpTransport transport = HttpTransports.okHttp();
                final RetryingHttpTransport retrying = new RetryingHttpTransport(transport, retry);
                final GitHubClient client = TestGitHubClients.raw(retrying, config, TestClients.auth())) {
            failures = runConcurrently(THREADS, thread -> () -> {
                final Thing thing = TestClients.caller(client).fetch(HttpCall.get("/flaky/" + thread), Thing.class);
                return thing.equals(new Thing("t" + thread))
                        ? List.of()
                        : List.of("thread " + thread + " got " + thing);
            });
        }

        assertThat(failures).isEmpty();
        for (int thread = 0; thread < THREADS; thread++) {
            HTTP.verifyRequested(GET, "/flaky/" + thread, 2);
        }
    }

    private static List<String> runConcurrently(final int threads, final IntFunction<Callable<List<String>>> task)
            throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            final List<Future<List<String>>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                final Callable<List<String>> work = task.apply(thread);
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            final List<String> all = new ArrayList<>();
            for (final Future<List<String>> future : futures) {
                all.addAll(future.get(60, TimeUnit.SECONDS));
            }
            return all;
        } finally {
            pool.shutdownNow();
        }
    }
}
