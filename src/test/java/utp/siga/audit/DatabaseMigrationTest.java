package utp.siga.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class DatabaseMigrationTest extends AuditIntegrationEnvironment {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliesMigrationsSuccessfullyOnAuditSchema() {
        assertEquals("siga_audit_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("siga_audit_test", jdbc.queryForObject("SELECT current_user", String.class));

        List<String> versions = jdbc.queryForList(
                "SELECT version FROM audit.flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);
        assertEquals(List.of("1", "2", "3"), versions, "Exactly V1-V3 must be applied");
    }

    @Test
    void expectedAuditTablesExist() {
        List<String> tables = jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'audit' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """,
                String.class);

        assertTrue(tables.contains("audit_event"), "audit.audit_event must exist");
        assertTrue(tables.contains("notification"), "audit.notification must exist");
        assertTrue(tables.contains("processed_event"), "audit.processed_event must exist");
        assertEquals(Set.of("audit_event", "notification", "processed_event"),
                tables.stream().filter(table -> !"flyway_schema_history".equals(table)).collect(java.util.stream.Collectors.toSet()),
                "Migrations must not create unrelated Audit domain tables");
    }

    @Test
    void noCrossSchemaOwnershipOrTablesExist() {
        List<String> otherServiceSchemas = jdbc.queryForList(
                """
                SELECT schema_name FROM information_schema.schemata
                WHERE schema_name IN ('inventory', 'catalog', 'identity', 'evidence')
                """,
                String.class);

        assertTrue(otherServiceSchemas.isEmpty(),
                "Audit service must not create or own other service schemas: " + otherServiceSchemas);
    }

    @Test
    void noCrossSchemaForeignKeys() {
        List<String> crossSchemaFks = jdbc.queryForList(
                """
                SELECT
                    tc.table_schema || '.' || tc.table_name || ' -> ' || ccu.table_schema || '.' || ccu.table_name
                FROM information_schema.table_constraints AS tc
                JOIN information_schema.key_column_usage AS kcu
                    ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage AS ccu
                    ON ccu.constraint_name = tc.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY'
                    AND tc.table_schema = 'audit'
                    AND ccu.table_schema <> 'audit'
                """,
                String.class);

        assertTrue(crossSchemaFks.isEmpty(),
                "Audit schema must have 0 cross-schema foreign keys, but found: " + crossSchemaFks);
    }
}
