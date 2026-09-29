package de.tum.cit.aet.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Runs the query helper that the generated code embeds with Node and checks the query strings it builds, see
 * {@code src/test/typescript/query-helper.test.mjs}.
 */
class QueryHelperTest {

    @Test
    void appendsQueryParametersTheWaySpringBindsThem() throws IOException, InterruptedException {
        Process node = new ProcessBuilder("node", "query-helper.test.mjs")
                .directory(Path.of("src/test/typescript").toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(node.waitFor(1, TimeUnit.MINUTES), "node did not finish");
        assertEquals(0, node.exitValue(), () -> "The query helper built a wrong query string:\n" + output);
    }
}
