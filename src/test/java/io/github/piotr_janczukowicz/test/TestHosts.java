package io.github.piotr_janczukowicz.test;

import java.net.URI;
import java.util.stream.Stream;

public final class TestHosts {

    public static final URI GITHUB = URI.create("https://api.github.com/");

    public static final URI GHEC = URI.create("https://api.octocorp.ghe.com/");

    public static final URI GHES = URI.create("https://ghe.example/api/v3/");

    private TestHosts() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Stream<URI> apiBases() {
        return Stream.of(GITHUB, GHEC, GHES);
    }
}
