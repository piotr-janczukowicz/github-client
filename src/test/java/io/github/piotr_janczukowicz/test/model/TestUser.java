package io.github.piotr_janczukowicz.test.model;

/** A small model for decoding tests: the two properties of a GitHub user the tests assert on. */
public record TestUser(long id, String login) {

    public static final TestUser OCTOCAT = new TestUser(1, "octocat");
}
