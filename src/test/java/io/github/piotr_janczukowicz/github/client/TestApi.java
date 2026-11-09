package io.github.piotr_janczukowicz.github.client;

import io.github.piotr_janczukowicz.github.client.pagination.GitHubListSpec;
import io.github.piotr_janczukowicz.github.pagination.GitHubPage;
import io.github.piotr_janczukowicz.github.pagination.GitHubPageRequest;
import io.github.piotr_janczukowicz.http.client.HttpCall;
import io.github.piotr_janczukowicz.uri.PathTemplate;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

final class TestApi extends GitHubResourceApi {

    TestApi(final GitHubCaller caller) {
        super(caller);
    }

    @Override
    public HttpCall get(final PathTemplate path, final Object... values) {
        return super.get(path, values);
    }

    @Override
    public HttpCall post(final PathTemplate path, final Object... values) {
        return super.post(path, values);
    }

    @Override
    public HttpCall post(final URI uri) {
        return super.post(uri);
    }

    @Override
    public HttpCall put(final PathTemplate path, final Object... values) {
        return super.put(path, values);
    }

    @Override
    public HttpCall patch(final PathTemplate path, final Object... values) {
        return super.patch(path, values);
    }

    @Override
    public HttpCall delete(final PathTemplate path, final Object... values) {
        return super.delete(path, values);
    }

    public void checkGhesVersion(final String since, final String until) {
        requireGhesVersion(since, until);
    }

    public void checkFlavor(final GitHubFlavor... available) {
        requireFlavor(available);
    }

    public <T> GitHubPage<T> values(
            final PathTemplate path,
            final Map<String, List<String>> query,
            final GitHubPageRequest request,
            final GitHubListSpec<T> spec)
            throws IOException {
        return listValues(path, query, request, spec);
    }
}
