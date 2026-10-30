package utp.siga.audit.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class IdentitySchemaParityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("schemas")
    void bundledRuntimeSchemaMatchesCanonicalDocument(String name) throws Exception {
        Path canonical = Path.of("..", "siga-documentation", "SIGA_Documentacion_Tecnica_Final_v1.2",
                "api", name);
        try (var bundled = getClass().getResourceAsStream("/schemas/identity/" + name)) {
            assertThat(bundled).as("bundled schema %s", name).isNotNull();
            assertThat(mapper.readTree(bundled)).isEqualTo(mapper.readTree(Files.readString(canonical)));
        }
    }

    static Stream<String> schemas() {
        return Stream.of(
                "iam-user-created-v1.schema.json", "iam-user-updated-v1.schema.json",
                "iam-user-disabled-v1.schema.json", "iam-role-changed-v1.schema.json",
                "iam-user-created-v2.schema.json", "iam-user-updated-v2.schema.json",
                "iam-user-disabled-v2.schema.json", "iam-user-roles-changed-v2.schema.json",
                "iam-role-created-v2.schema.json", "iam-role-permissions-changed-v2.schema.json");
    }
}
