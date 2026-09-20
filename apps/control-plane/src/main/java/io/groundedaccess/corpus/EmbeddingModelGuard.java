package io.groundedaccess.corpus;

import io.groundedaccess.modelclient.EmbeddingClient;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the active corpus was embedded with a different model than the configured one. Vectors from two models share a dimension but
 * not a vector space, so mixing them would return silently meaningless neighbours instead of an error.
 */
@Component
class EmbeddingModelGuard implements ApplicationRunner {

    private final JdbcClient jdbc;

    private final EmbeddingClient embeddings;

    EmbeddingModelGuard(JdbcClient jdbc, EmbeddingClient embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    @Override
    public void run(ApplicationArguments args) {
        check();
    }

    void check() {
        String configured = embeddings.modelName();
        List<String> foreign = jdbc.sql("""
                        select distinct v.embedding_model
                        from document_version v join document d on d.active_version_id = v.id
                        where split_part(v.embedding_model, '@', 1) <> :model
                        """)
                .param("model", configured)
                .query((rs, i) -> Objects.requireNonNullElse(rs.getString(1), "unknown"))
                .list();
        if (!foreign.isEmpty()) {
            throw new IllegalStateException("Active documents were embedded with %s but the configured embedding model is %s. Re-ingest the corpus "
                    .formatted(foreign, configured) + "into an empty database, or configure the previous model again.");
        }
    }
}
