package de.tum.cit.aet.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

class QueryHelperTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsQueryParametersTheWaySpringBindsThem() throws IOException, InterruptedException {
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(Angular22Generator.GENERATOR_NAME)
                .setInputSpec(Path.of("src/test/resources/fixtures/object-query-openapi.yaml").toAbsolutePath().toString())
                .setOutputDir(tempDir.toString());
        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();

        Process node = new ProcessBuilder("node", "query-helper.test.mjs", tempDir.resolve("api/query-params.ts").toString())
                .directory(Path.of("src/test/typescript").toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(node.waitFor(1, TimeUnit.MINUTES), "node did not finish");
        assertEquals(0, node.exitValue(), () -> "The query helper built a wrong query string:\n" + output);
    }
}
