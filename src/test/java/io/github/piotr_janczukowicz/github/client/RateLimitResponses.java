package io.github.piotr_janczukowicz.github.client;

import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_LIMIT;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_REMAINING;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESET;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_RESOURCE;
import static io.github.piotr_janczukowicz.github.GitHubHeaders.RATE_LIMIT_USED;
import static io.github.piotr_janczukowicz.http.HttpHeaderNames.DATE;
import static io.github.piotr_janczukowicz.test.http.HttpResponses.response;

import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.util.DateTimeUtils;
import java.time.Instant;

final class RateLimitResponses {

    static final String SECONDARY_BODY = "{\"message\":\"You have exceeded a secondary rate limit. Please wait a few"
            + " minutes before you try again.\",\"documentation_url\":\"https://docs.github.com/rest/overview/"
            + "rate-limits-for-the-rest-api#about-secondary-rate-limits\"}";

    static final String PERMISSION_BODY = "{\"message\":\"Resource not accessible by integration\","
            + "\"documentation_url\":\"https://docs.github.com/rest\"}";

    static final String PRIMARY_BODY = "{\"message\":\"API rate limit exceeded for user ID 1.\","
            + "\"documentation_url\":\"https://docs.github.com/rest/overview/rate-limits-for-the-rest-api\"}";

    private RateLimitResponses() {
        throw new UnsupportedOperationException("Utility class");
    }

    static HttpResponse ok() {
        return response(HttpStatus.OK.code(), null);
    }

    static HttpResponse status(final HttpStatus status) {
        return response(status.code(), null);
    }

    static HttpResponse primary(
            final HttpStatus status, final Instant date, final Instant reset, final String resource) {
        return response(
                status.code(),
                PRIMARY_BODY,
                DATE,
                DateTimeUtils.toRfc1123(date),
                RATE_LIMIT_LIMIT,
                "5000",
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_USED,
                "5000",
                RATE_LIMIT_RESET,
                epoch(reset),
                RATE_LIMIT_RESOURCE,
                resource);
    }

    static HttpResponse primaryWithoutResource(final HttpStatus status, final Instant date, final Instant reset) {
        return response(
                status.code(),
                PRIMARY_BODY,
                DATE,
                DateTimeUtils.toRfc1123(date),
                RATE_LIMIT_LIMIT,
                "5000",
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_USED,
                "5000",
                RATE_LIMIT_RESET,
                epoch(reset));
    }

    static HttpResponse spent(final HttpStatus status, final Instant date, final Instant reset) {
        return response(
                status.code(),
                null,
                DATE,
                DateTimeUtils.toRfc1123(date),
                RATE_LIMIT_LIMIT,
                "5000",
                RATE_LIMIT_REMAINING,
                "0",
                RATE_LIMIT_USED,
                "5000",
                RATE_LIMIT_RESET,
                epoch(reset),
                RATE_LIMIT_RESOURCE,
                "core");
    }

    static HttpResponse secondary(final HttpStatus status) {
        return response(status.code(), SECONDARY_BODY);
    }

    static String epoch(final Instant instant) {
        return Long.toString(instant.getEpochSecond());
    }
}
