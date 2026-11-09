/**
 * Client for the GitHub REST API. {@link io.github.piotr_janczukowicz.github.client.GitHubClient} sends the requests;
 * {@link io.github.piotr_janczukowicz.github.client.GitHubClients} builds it with the always-on request stack (retry,
 * mutation pacing, concurrency limit, OkHttp transport), configured by
 * {@link io.github.piotr_janczukowicz.github.client.GitHubClientConfiguration}. GitHub App authentication is in
 * {@code github.client.app}.
 */
@NullMarked
package io.github.piotr_janczukowicz.github.client;

import org.jspecify.annotations.NullMarked;
