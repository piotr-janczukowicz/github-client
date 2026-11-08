module io.github.piotr_janczukowicz.githubclient {
    requires transitive okhttp3;
    requires okio;
    requires transitive tools.jackson.databind;
    requires org.slf4j;
    requires org.jspecify;
    requires transitive common.core;
    requires static io.soabase.recordbuilder.core;
    requires static java.compiler;

    exports io.github.piotr_janczukowicz.github;
    exports io.github.piotr_janczukowicz.github.client;
    exports io.github.piotr_janczukowicz.github.client.app;
    exports io.github.piotr_janczukowicz.github.client.exception;
    exports io.github.piotr_janczukowicz.github.client.pagination;
    exports io.github.piotr_janczukowicz.github.client.ratelimit;
    exports io.github.piotr_janczukowicz.github.json;
    exports io.github.piotr_janczukowicz.github.pagination;

    opens io.github.piotr_janczukowicz.github.client to
            tools.jackson.databind,
            common.core;
    opens io.github.piotr_janczukowicz.github.client.app to
            tools.jackson.databind,
            common.core;
    opens io.github.piotr_janczukowicz.github.client.exception to
            tools.jackson.databind,
            common.core;
    opens io.github.piotr_janczukowicz.github.client.pagination to
            tools.jackson.databind,
            common.core;
    opens io.github.piotr_janczukowicz.github.client.ratelimit to
            tools.jackson.databind,
            common.core;
    opens io.github.piotr_janczukowicz.github.json to
            tools.jackson.databind,
            common.core;
    opens io.github.piotr_janczukowicz.github.pagination to
            tools.jackson.databind,
            common.core;

    uses io.github.piotr_janczukowicz.github.json.GitHubJsonModule;
}
