package io.github.piotr_janczukowicz.github.client.app;

import static io.github.piotr_janczukowicz.util.ConcurrencyUtils.toInterruptedIo;
import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cached installation token of one {@link GitHubInstallationAuthenticationProvider}: when it is renewed,
 * single-flight fetches and the failure backoff. The token itself is fetched by an
 * {@link GitHubInstallationTokenFetcher}.
 */
final class GitHubInstallationTokenCache {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubInstallationTokenCache.class);

    private final long installationId;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration minRenewInterval;
    private final Clock clock;
    private final GitHubInstallationTokenFetcher fetcher;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final AtomicReference<@Nullable CachedToken> cached = new AtomicReference<>();
    // Written only under refreshLock.
    private final AtomicReference<@Nullable Failure> failure = new AtomicReference<>();

    GitHubInstallationTokenCache(
            final long installationId,
            final Duration initialBackoff,
            final Duration maxBackoff,
            final Duration minRenewInterval,
            final Clock clock,
            final GitHubInstallationTokenFetcher fetcher) {
        this.installationId = installationId;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.minRenewInterval = minRenewInterval;
        this.clock = clock;
        this.fetcher = fetcher;
    }

    // S2142: interruptedWhileWaiting() calls toInterruptedIo(), which restores the interrupt flag; Sonar cannot see
    // that.
    @SuppressWarnings("java:S2142")
    String token() {
        final CachedToken current = cached.get();
        final Instant now = clock.instant();
        if (current != null && now.isBefore(current.refreshAt())) {
            return current.value();
        }
        if (current != null && now.isBefore(current.expiresAt())) {
            if (!refreshLock.tryLock()) {
                return current.value();
            }
        } else {
            try {
                refreshLock.lockInterruptibly();
            } catch (final InterruptedException interrupted) {
                throw interruptedWhileWaiting(interrupted);
            }
        }
        try {
            return renew();
        } finally {
            refreshLock.unlock();
        }
    }

    void invalidate() {
        cached.set(null);
    }

    void invalidate(final String staleToken) {
        requireNonNull(staleToken, "staleToken");
        cached.updateAndGet(current -> current != null && current.value().equals(staleToken) ? null : current);
    }

    boolean isRenewableAfterRejection(final String token) {
        final CachedToken current = cached.get();
        if (current == null || !current.value().equals(token)) {
            LOG.atDebug()
                    .setMessage("Rejected token of installation {} is no longer the cached one; a new one is used")
                    .addArgument(installationId)
                    .log();
            return true;
        }
        if (clock.instant().isBefore(current.fetchedAt().plus(minRenewInterval))) {
            LOG.atDebug()
                    .setMessage("Installation {} token was rejected {} after it was fetched; not renewing it before {}")
                    .addArgument(installationId)
                    .addArgument(() -> Duration.between(current.fetchedAt(), clock.instant()))
                    .addArgument(minRenewInterval)
                    .log();
            return false;
        }
        invalidate(token);
        LOG.atDebug()
                .setMessage("Installation {} token was rejected; dropped it so the next call fetches a new one")
                .addArgument(installationId)
                .log();
        return true;
    }

    private void logBackoff(final Instant retryAt, final boolean hasUnexpiredToken) {
        LOG.atDebug()
                .setMessage("Not fetching a token for installation {} before {} after an earlier failure; {}")
                .addArgument(installationId)
                .addArgument(retryAt)
                .addArgument(hasUnexpiredToken ? "using the unexpired token" : "no usable token")
                .log();
    }

    private static UncheckedIOException interruptedWhileWaiting(final InterruptedException interrupted) {
        return new UncheckedIOException(
                "interrupted while waiting for an installation token",
                toInterruptedIo(interrupted, "interrupted while waiting for a token"));
    }

    /** Renews the token; the caller holds {@code refreshLock}. */
    private String renew() {
        final Instant now = clock.instant();
        final CachedToken latest = cached.get();
        if (latest != null && now.isBefore(latest.refreshAt())) {
            return latest.value();
        }
        final CachedToken unexpired = latest != null && now.isBefore(latest.expiresAt()) ? latest : null;
        final Failure last = failure.get();
        if (last != null && now.isBefore(last.retryAt())) {
            logBackoff(last.retryAt(), unexpired != null);
            if (unexpired != null) {
                return unexpired.value();
            }
            throw new GitHubInstallationTokenUnavailableException(last.retryAt(), last.error());
        }
        final CachedToken fetched;
        try {
            fetched = fetcher.fetch(installationId);
        } catch (final RuntimeException error) {
            return afterFailedFetch(error, last, unexpired);
        }
        failure.set(null);
        cached.set(fetched);
        LOG.atDebug()
                .setMessage("Fetched an access token for installation {}; it expires at {}")
                .addArgument(installationId)
                .addArgument(fetched.expiresAt())
                .log();
        return fetched.value();
    }

    private String afterFailedFetch(
            final RuntimeException error, final @Nullable Failure last, final @Nullable CachedToken unexpired) {
        if (isInterrupt(error)) {
            throw error;
        }
        final Duration backoff = last == null ? initialBackoff : nextBackoff(last.backoff());
        final Instant retryAt = clock.instant().plus(backoff);
        failure.set(new Failure(error, retryAt, backoff));
        LOG.atDebug()
                .setMessage("Fetching an access token for installation {} failed with {}; no new attempt before {}")
                .addArgument(installationId)
                .addArgument(error.getClass().getName())
                .addArgument(retryAt)
                .log();
        return unexpiredOrThrow(unexpired, error);
    }

    private static String unexpiredOrThrow(final @Nullable CachedToken unexpired, final RuntimeException error) {
        if (unexpired != null) {
            return unexpired.value();
        }
        throw error;
    }

    private Duration nextBackoff(final Duration previous) {
        final Duration doubled = previous.multipliedBy(2);
        return doubled.compareTo(maxBackoff) < 0 ? doubled : maxBackoff;
    }

    // An InterruptedIOException is also what a call timeout raises, and that one is a transient failure that must back
    // off. A real interrupt leaves the flag set, in the retry pause and in OkHttp alike.
    private static boolean isInterrupt(final RuntimeException error) {
        return error instanceof UncheckedIOException
                && error.getCause() instanceof InterruptedIOException
                && Thread.currentThread().isInterrupted();
    }

    /**
     * {@code refreshAt} is when the token starts to be renewed; it is never after {@code expiresAt}. {@code toString}
     * leaves out {@code value}, the token itself.
     */
    record CachedToken(String value, Instant fetchedAt, Instant expiresAt, Instant refreshAt) {

        @Override
        public String toString() {
            return "CachedToken[fetchedAt=" + fetchedAt + ", expiresAt=" + expiresAt + ", refreshAt=" + refreshAt + "]";
        }
    }

    private record Failure(RuntimeException error, Instant retryAt, Duration backoff) {}
}
