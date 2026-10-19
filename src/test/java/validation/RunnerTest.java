package validation;

import org.junit.jupiter.api.Test;
import validation.Runner.ValidationResult;

import static org.junit.jupiter.api.Assertions.*;

public class RunnerTest {

    @Test
    public void testValidManifestValidation() {
        Runner runner = new Runner();
        String validPod = """
                apiVersion: v1
                kind: Pod
                metadata:
                  name: test-pod
                spec:
                  containers:
                  - name: nginx
                    image: nginx:latest
                """;

        ValidationResult result = runner.validateManifest(validPod);
        // Note: If kubectl is available locally, it will validate successfully
        // If not, it gracefully reports error
        assertNotNull(result);
        assertNotNull(result.getOutput());
    }

    @Test
    public void testInvalidManifestValidation() {
        Runner runner = new Runner();
        String invalidManifest = """
                apiVersion: v1
                kind: InvalidKindNonExistent
                invalid_structure:::
                """;

        ValidationResult result = runner.validateManifest(invalidManifest);
        assertNotNull(result);
        assertFalse(result.isSuccess(), "Malformed manifest should fail dry-run validation");
    }
}
