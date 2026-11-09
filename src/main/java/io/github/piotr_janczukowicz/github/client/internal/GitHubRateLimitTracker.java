package io.github.piotr_janczukowicz.github.client.internal;

import static io.github.piotr_janczukowicz.util.ValidationUtils.requireNonNull;

import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitListener;
import io.github.piotr_janczukowicz.github.client.ratelimit.GitHubRateLimitSnapshot;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers the latest quota received per resource and forwards every observed one to the listener. The remaining count
 * can go up as well as down within a window, so the most recently received snapshot of a window is kept, not the
 * smallest.
 */
public final class GitHubRateLimitTracker {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubRateLimitTracker.class);

    private final @Nullable GitHubRateLimitListener listener;
    private final Clock clock;
    private final ConcurrentMap<String, GitHubRateLimitSnapshot> latest = new ConcurrentHashMap<>();

    /**
     * Tracker that forwards every observed quota to {@code listener}.
     *
     * @param listener receives each observed quota, or {@code null} for none; a listener that throws is logged and
     *     ignored
     * @param clock clock that stamps the observation
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public GitHubRateLimitTracker(final @Nullable GitHubRateLimitListener listener, final Clock clock) {
        this.listener = listener;
        this.clock = requireNonNull(clock, "clock");
    }

    /**
     * Records the quota that {@code headers} carry, if any, and forwards it to the listener.
     *
     * @param headers response headers
     */
    public void observe(final HttpHeaders headers) {
        final Optional<GitHubRateLimitSnapshot> snapshot = GitHubRateLimitSnapshot.from(headers, clock.instant());
        if (snapshot.isEmpty()) {
            return;
        }
        final GitHubRateLimitSnapshot observed = snapshot.orElseThrow();
        storeIfNewer(observed);
        notifyListener(observed);
    }

    /**
     * Latest quota seen for {@code resource}.
     *
     * @param resource resource name, such as {@code core}
     * @return the snapshot, or empty when none was seen
     * @throws NullPointerException if {@code resource} is {@code null}
     */
    public Optional<GitHubRateLimitSnapshot> last(final String resource) {
        return Optional.ofNullable(latest.get(requireNonNull(resource, "resource")));
    }

    /**
     * Latest quota of every resource seen.
     *
     * @return immutable copy, keyed by resource name
     */
    public Map<String, GitHubRateLimitSnapshot> all() {
        return Map.copyOf(latest);
    }

    // One atomic update per observed quota: nothing is written when the stored quota is newer or unchanged, and the
    // quota is logged only when it replaced the stored one.
    private void storeIfNewer(final GitHubRateLimitSnapshot observed) {
        final AtomicBoolean replaced = new AtomicBoolean();
        latest.compute(observed.resource(), (resource, old) -> {
            if (old == null || isReplacing(observed, old)) {
                replaced.set(true);
                return observed;
            }
            return old;
        });
        if (replaced.get()) {
            logQuota(observed);
        }
    }

    private static void logQuota(final GitHubRateLimitSnapshot observed) {
        LOG.atDebug()
                .setMessage("Rate limit {}: {} of {} remaining, resets at {}")
                .addArgument(observed.resource())
                .addArgument(observed.remaining())
                .addArgument(observed.limit())
                .addArgument(observed.resetAt())
                .log();
    }

    // A later window wins and an earlier one never does. Within one window the most recently received snapshot wins,
    // whether its remaining count went up or down: GitHub can serve responses of one window from several regions, so
    // the count is not monotonic. An identical quota is not written, so the stored observedAt is the first observation
    // of that quota.
    private static boolean isReplacing(final GitHubRateLimitSnapshot fresh, final GitHubRateLimitSnapshot old) {
        if (fresh.resetAt().isAfter(old.resetAt())) {
            return true;
        }
        if (!fresh.resetAt().equals(old.resetAt())) {
            return false;
        }
        return fresh.remaining() != old.remaining() || fresh.limit() != old.limit() || fresh.used() != old.used();
    }

    private void notifyListener(final GitHubRateLimitSnapshot snapshot) {
        if (listener == null) {
            return;
        }
        try {
            listener.onRateLimit(snapshot);
        } catch (final RuntimeException failure) {
            LOG.atWarn()
                    .setMessage("Rate-limit listener failed for resource {}: {}")
                    .addArgument(snapshot.resource())
                    .addArgument(failure.toString())
                    .log();
        }
    }
}
