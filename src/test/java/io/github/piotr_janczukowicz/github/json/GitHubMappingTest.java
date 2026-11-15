package io.github.piotr_janczukowicz.github.json;

import io.github.piotr_janczukowicz.json.JsonCodec;
import io.github.piotr_janczukowicz.testsupport.mapping.AbstractMappingTest;

abstract class GitHubMappingTest extends AbstractMappingTest {

    @Override
    protected JsonCodec codec() {
        return GitHubJsonCodecs.defaults();
    }
}
