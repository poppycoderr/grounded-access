package io.groundedaccess.retrieval;

import io.groundedaccess.authorization.AuthorizationPredicate;
import io.groundedaccess.corpus.Vectors;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The only reader of the chunk table. Every query embeds the compiled authorization predicate and restricts candidates to the active version of an
 * active document, so unauthorized or stale rows never leave the database.
 */
@Repository
public class AuthorizedChunkQuery {

    private static final String SELECT = """
            select c.id, d.external_key, v.version_no, v.title, c.section_path, c.char_start, c.char_end, c.content, %s as score
            from chunk c
            join document d on d.active_version_id = c.version_id and d.status = 'active'
            join document_version v on v.id = c.version_id
            where (%s)
            """;

    // Ties are broken by stable keys, never by the random chunk id, so repeated evaluation runs rank identically
    private static final String TIE_BREAK = "d.external_key, v.version_no, c.ordinal";

    private final JdbcClient jdbc;

    public AuthorizedChunkQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Full-text search with OR semantics: plainto_tsquery ANDs every lexeme, so a natural-language question would rarely match any chunk; the
     * lexemes are re-joined with '|' and ranked with ts_rank_cd. This is PostgreSQL FTS ranking, not BM25 (ADR-0002).
     */
    public List<RetrievedChunk> sparse(String query, AuthorizationPredicate predicate, int limit) {
        String tsquery = "cast(replace(plainto_tsquery('english', :query)::text, '&', '|') as tsquery)";
        String sql = SELECT.formatted("ts_rank_cd(c.content_tsv, " + tsquery + ")", predicate.sql())
                + " and c.content_tsv @@ " + tsquery + " order by score desc, " + TIE_BREAK + " limit :limit";
        return run(sql, predicate, RetrievalChannel.SPARSE, limit, "query", query);
    }

    /**
     * Exact cosine search over authorized rows; there is deliberately no ANN index in v0.1, so filtering cannot reduce recall (ADR-0002).
     */
    public List<RetrievedChunk> dense(float[] queryVector, AuthorizationPredicate predicate, int limit) {
        String distance = "(c.embedding <=> cast(:vector as vector))";
        String sql = SELECT.formatted("1 - " + distance, predicate.sql()) + " and c.embedding is not null order by " + distance + ", " + TIE_BREAK + " limit :limit";
        return run(sql, predicate, RetrievalChannel.DENSE, limit, "vector", Vectors.toLiteral(queryVector));
    }

    private List<RetrievedChunk> run(String sql, AuthorizationPredicate predicate, RetrievalChannel channel, int limit, String name, String value) {
        return jdbc.sql(sql)
                .params(predicate.parameters())
                .param(name, value)
                .param("limit", limit)
                .query((rs, rowNum) -> map(rs, channel, rowNum + 1))
                .list();
    }

    private static RetrievedChunk map(ResultSet rs, RetrievalChannel channel, int rank) throws SQLException {
        return new RetrievedChunk(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getInt(6),
                rs.getInt(7), rs.getString(8), channel, rank, rs.getDouble(9));
    }
}
