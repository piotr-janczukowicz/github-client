package io.github.piotr_janczukowicz.github.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.piotr_janczukowicz.github.client.exception.GitHubNotFoundException;
import io.github.piotr_janczukowicz.http.HttpBody;
import io.github.piotr_janczukowicz.http.HttpHeaders;
import io.github.piotr_janczukowicz.http.HttpMethod;
import io.github.piotr_janczukowicz.http.HttpResponse;
import io.github.piotr_janczukowicz.http.HttpStatus;
import io.github.piotr_janczukowicz.testsupport.UnitTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

@UnitTest
class GitHubErrorMapperTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void map_shouldReturnNotFoundWithGitHubMessage_whenDefaultCodecAndStatus404() {
        final GitHubErrorMapper mapper = new GitHubErrorMapper(CLOCK);
        final HttpResponse response = new HttpResponse(
                HttpStatus.NOT_FOUND.code(),
                HttpHeaders.empty(),
                HttpBody.of("{\"message\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8)));

        final RuntimeException exception =
                mapper.map(HttpMethod.GET, response, URI.create("https://api.github.com/user"));

        assertThat(exception).isInstanceOf(GitHubNotFoundException.class).hasMessageContaining("Not Found");
    }
}
