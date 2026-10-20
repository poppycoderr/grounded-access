package io.groundedaccess.modelclient;

import java.util.Optional;

/**
 * What the model service says it has loaded. Used to describe the deployment, never to decide a request.
 */
public interface ModelCatalog {

    /**
     * The revision of the configured reranker, or empty when the model service cannot be reached or has another reranker loaded.
     */
    Optional<String> rerankerRevision();
}
