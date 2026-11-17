package com.physicscube.luma.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The production alarms count fixed phrases in the API's log (infra/terraform/modules/monitoring).
 * If someone rewords a log line, the alarm would go quiet without anyone noticing; this test fails instead.
 */
class AlarmLogPhrasesTest {
    private static final Path MONITORING = Path.of("..", "infra", "terraform", "modules", "monitoring", "main.tf");
    private static final Pattern FILTER = Pattern.compile("pattern\\s*=\\s*\"\\\\\"(.+?)\\\\\"\"");

    @Test
    void everyLogPhraseAnAlarmCountsIsStillWrittenByTheApi() throws IOException {
        // The full repository is checked out in CI; the Docker test script mounts only backend/.
        assumeTrue(Files.exists(MONITORING), "infra/ is not available here");
        List<String> phrases = new ArrayList<>();
        Matcher m = FILTER.matcher(Files.readString(MONITORING));
        while (m.find()) {
            phrases.add(m.group(1));
        }
        assertFalse(phrases.isEmpty(), "no log filters found; did the Terraform layout change?");

        StringBuilder logLines = new StringBuilder();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (line.contains("log.")) {
                        logLines.append(line).append('\n');
                    }
                }
            }
        }
        for (String phrase : phrases) {
            assertTrue(logLines.toString().contains(phrase), "no log statement writes \"" + phrase + "\"");
        }
    }
}
