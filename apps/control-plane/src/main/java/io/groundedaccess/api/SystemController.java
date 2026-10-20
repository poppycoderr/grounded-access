package io.groundedaccess.api;

import io.groundedaccess.corpus.IndexProfiles;
import io.groundedaccess.modelclient.ModelCatalog;
import io.groundedaccess.modelclient.RerankClient;
import io.groundedaccess.retrieval.RetrievalProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Describes the deployment for evaluation manifests. Needs the {@code debug} scope.
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final IndexProfiles profiles;

    private final ModelCatalog models;

    private final RerankClient reranker;

    private final RetrievalProperties retrieval;

    private final SystemResponse.Build build;

    public SystemController(IndexProfiles profiles, ModelCatalog models, RerankClient reranker, RetrievalProperties retrieval,
            @Value("${ga.build.version:unknown}") String version, @Value("${ga.build.commit:unknown}") String commit) {
        this.profiles = profiles;
        this.models = models;
        this.reranker = reranker;
        this.retrieval = retrieval;
        this.build = new SystemResponse.Build(version, commit.isBlank() ? "unknown" : commit);
    }

    @GetMapping
    public SystemResponse describe() {
        return new SystemResponse(build, profiles.current().map(SystemResponse.IndexProfile::from).orElse(null),
                new SystemResponse.Reranker(reranker.modelName(), models.rerankerRevision().orElse(null)), retrieval,
                new SystemResponse.Host(cpu(), Runtime.getRuntime().availableProcessors()));
    }

    /**
     * The CPU model on Linux, where published runs are made; elsewhere the architecture.
     */
    private static String cpu() {
        try (Stream<String> lines = Files.lines(Path.of("/proc/cpuinfo"))) {
            return lines.filter(line -> line.startsWith("model name"))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip())
                    .findFirst()
                    .orElse(System.getProperty("os.arch"));
        } catch (IOException e) {
            return System.getProperty("os.arch");
        }
    }
}
