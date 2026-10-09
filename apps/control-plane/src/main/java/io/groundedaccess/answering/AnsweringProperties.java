package io.groundedaccess.answering;

import io.groundedaccess.retrieval.RetrievalStrategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the answering step: which retrieval strategy feeds it, how many chunks it asks for and how many words of evidence go into the
 * prompt.
 */
@ConfigurationProperties("ga.answering")
public record AnsweringProperties(
        RetrievalStrategy strategy,

        int evidenceLimit,

        int contextMaxWords) {
}
