package io.groundedaccess.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.groundedaccess.DemoTokens;
import io.groundedaccess.HashingEmbeddingClient;
import io.groundedaccess.TestcontainersConfiguration;
import io.groundedaccess.modelclient.EmbeddingClient;
import io.groundedaccess.modelclient.ModelCatalog;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What the deployment says about itself for evaluation manifests (ADR-0008): only to debug tokens, and nothing about any tenant.
 */
@SpringBootTest(properties = {"ga.ingestion.worker.enabled=false", "ga.corpus.cleanup.enabled=false", "ga.build.commit=abc1234"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SystemIT.FakeModels.class})
class SystemIT {

    @TestConfiguration
    static class FakeModels {

        @Bean
        @Primary
        EmbeddingClient hashingEmbeddingClient() {
            return new HashingEmbeddingClient();
        }

        @Bean
        @Primary
        ModelCatalog modelCatalog() {
            return () -> Optional.of("rerank-rev");
        }
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void describesTheBuildTheIndexTheRerankerTheSettingsAndTheHost() throws Exception {
        String body = mvc.perform(get("/api/v1/system").header("Authorization", "Bearer " + DemoTokens.token("observer-7", "northstar", "query debug")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.build.commit").value("abc1234"))
                .andExpect(jsonPath("$.build.version").isNotEmpty())
                .andExpect(jsonPath("$.indexProfile.embeddingModel").value("hashing"))
                .andExpect(jsonPath("$.indexProfile.embeddingRevision").value("test"))
                .andExpect(jsonPath("$.indexProfile.chunkMaxWords").value(180))
                .andExpect(jsonPath("$.reranker.revision").value("rerank-rev"))
                .andExpect(jsonPath("$.retrieval.candidates").value(50))
                .andExpect(jsonPath("$.host.processors").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("northstar", "observer-7", "tenant", "principal", "document");
    }

    @Test
    void needsTheDebugScope() throws Exception {
        mvc.perform(get("/api/v1/system").header("Authorization", "Bearer " + DemoTokens.token("reader", "northstar", "query")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/system")).andExpect(status().isUnauthorized());
    }
}
