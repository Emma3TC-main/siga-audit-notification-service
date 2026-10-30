package utp.siga.audit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TestDatabaseSafetyTest {

    @Test
    void acceptsOnlyDedicatedAuditTestDatabaseAndLogin() {
        MockEnvironment environment = safeEnvironment();

        assertDoesNotThrow(() -> TestDatabaseSafety.validate(environment));
    }

    @Test
    void rejectsSharedOrOperationalDatabase() {
        MockEnvironment environment = safeEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:15432/siga");

        assertThrows(IllegalStateException.class, () -> TestDatabaseSafety.validate(environment));
    }

    @Test
    void rejectsAlternateConnectionProperties() {
        MockEnvironment environment = safeEnvironment()
                .withProperty("spring.flyway.url", "jdbc:postgresql://127.0.0.1:15432/siga");

        assertThrows(IllegalStateException.class, () -> TestDatabaseSafety.validate(environment));
    }

    private static MockEnvironment safeEnvironment() {
        return new MockEnvironment()
                .withProperty("audit.test.expected-database", "siga_audit_test")
                .withProperty("audit.test.expected-user", "siga_audit_test")
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:54321/siga_audit_test")
                .withProperty("spring.datasource.username", "siga_audit_test")
                .withProperty("spring.flyway.schemas", "audit")
                .withProperty("spring.flyway.default-schema", "audit");
    }
}
