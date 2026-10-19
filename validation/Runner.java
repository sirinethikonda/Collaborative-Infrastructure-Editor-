package validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class Runner {

    private static final Logger log = LoggerFactory.getLogger(Runner.class);

    public static class ValidationResult {
        private final boolean success;
        private final String output;

        public ValidationResult(boolean success, String output) {
            this.success = success;
            this.output = output;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }
    }

    /**
     * Writes content to a temporary YAML file and runs `kubectl apply --dry-run=client -f <file>`
     */
    public ValidationResult validateManifest(String manifestContent) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("k8s-manifest-", ".yaml");
            Files.writeString(tempFile, manifestContent, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                    "kubectl",
                    "apply",
                    "--dry-run=client",
                    "-f",
                    tempFile.toAbsolutePath().toString()
            );

            // Redirect error stream so we can capture combined output
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder outputBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (outputBuilder.length() > 0) {
                        outputBuilder.append("\n");
                    }
                    outputBuilder.append(line);
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ValidationResult(false, "Validation timed out after 10 seconds");
            }

            int exitCode = process.exitValue();
            String output = outputBuilder.toString().trim();

            if (exitCode == 0) {
                log.info("Kubectl validation succeeded: {}", output);
                return new ValidationResult(true, output.isEmpty() ? "validation successful" : output);
            } else {
                log.warn("Kubectl validation failed (exit code {}): {}", exitCode, output);
                return new ValidationResult(false, output.isEmpty() ? "validation failed with code " + exitCode : output);
            }

        } catch (Exception e) {
            log.error("Exception during kubectl execution", e);
            return new ValidationResult(false, "Failed to execute kubectl validation: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
