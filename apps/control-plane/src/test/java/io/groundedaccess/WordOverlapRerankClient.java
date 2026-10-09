package io.groundedaccess;

import io.groundedaccess.modelclient.RerankClient;
import io.groundedaccess.modelclient.RerankScores;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic stand-in for the cross-encoder: a passage scores the share of the query's words it contains.
 */
public class WordOverlapRerankClient implements RerankClient {

    @Override
    public String modelName() {
        return "word-overlap";
    }

    @Override
    public RerankScores score(String query, List<String> passages) {
        Set<String> wanted = words(query);
        return new RerankScores("word-overlap", "test", passages.stream()
                .map(passage -> wanted.isEmpty() ? 0.0 : (double) words(passage).stream().filter(wanted::contains).count() / wanted.size())
                .toList());
    }

    private static Set<String> words(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).filter(word -> !word.isEmpty()).collect(Collectors.toSet());
    }
}
