package de.tum.cit.aet.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

class GeneratedCodeCompilesTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures");
    private static final Path TYPESCRIPT = Path.of("src/test/typescript").toAbsolutePath();

    private static final Map<String, Map<String, String>> OPTION_SETS = Map.of(
            "resources", Map.of(),
            "inline-resources", Map.of("separateResources", "false"),
            "observables-only", Map.of("useHttpResource", "false", "separateResources", "false"));

    @TempDir
    Path tempDir;

    @Test
    void generatedCodeOfEveryFixtureCompiles() throws IOException, InterruptedException {
        List<Path> fixtures;
        try (Stream<Path> files = Files.list(FIXTURES)) {
            fixtures = files.filter(file -> file.toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path fixture : fixtures) {
            String name = fixture.getFileName().toString().replace(".yaml", "");
            OPTION_SETS.forEach((options, properties) -> generate(fixture, tempDir.resolve(name).resolve(options), properties));
        }
        Files.createSymbolicLink(tempDir.resolve("node_modules"), TYPESCRIPT.resolve("node_modules"));
        Files.writeString(tempDir.resolve("tsconfig.json"), """
                { "extends": "%s", "include": ["**/*.ts"], "exclude": ["node_modules"] }
                """.formatted(TYPESCRIPT.resolve("tsconfig.json")));

        Process tsc = new ProcessBuilder("node", TYPESCRIPT.resolve("node_modules/typescript/bin/tsc").toString(), "-p", "tsconfig.json")
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(tsc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(tsc.waitFor(2, TimeUnit.MINUTES), "tsc did not finish");
        assertEquals(0, tsc.exitValue(), () -> "Generated code does not compile:\n" + output);
    }

    private static void generate(Path spec, Path outputDir, Map<String, String> additionalProperties) {
        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(Angular22Generator.GENERATOR_NAME)
                .setInputSpec(spec.toAbsolutePath().toString())
                .setOutputDir(outputDir.toString());
        additionalProperties.forEach(configurator::addAdditionalProperty);
        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }
}
