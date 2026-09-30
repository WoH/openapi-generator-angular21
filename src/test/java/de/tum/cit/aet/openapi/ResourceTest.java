package de.tum.cit.aet.openapi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

class ResourceTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsTheRequestFromTheResourceArguments() throws IOException, InterruptedException {
        generate(tempDir.resolve("resources"), Map.of());
        generate(tempDir.resolve("inline"), Map.of("separateResources", "false"));

        for (Path file : new Path[] {tempDir.resolve("resources/api/review-resources.ts"), tempDir.resolve("inline/api/review-api.ts")}) {
            NodeProcess.assertSucceeds(Path.of("src/test/typescript"), "A resource built the wrong request from " + file.getFileName(),
                    "resource.test.mjs", file.toString());
        }
    }

    private static void generate(Path outputDir, Map<String, String> additionalProperties) {
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(Angular22Generator.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/fixtures/resource-inputs-openapi.yaml").toAbsolutePath().toString())
                .setOutputDir(outputDir.toString());
        additionalProperties.forEach(configurator::addAdditionalProperty);
        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }
}
