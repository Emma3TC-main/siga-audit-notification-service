package utp.siga.audit;

import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;

@ContextConfiguration(initializers = TestDatabaseSafety.class)
public abstract class AuditIntegrationEnvironment {

    protected static final String TEST_QUEUE = "audit.movement.confirmed.v1";
    protected static final String TEST_EXCHANGE = "siga.events";
    protected static final String TEST_ROUTING_KEY = "inventory.movement.confirmed.v1";
    protected static final String TEST_NOTIFICATION_QUEUE = "notifications.movement.confirmed.v1";

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("siga_audit_test")
            .withUsername("siga_audit_test")
            .withPassword("audit-test-password")
            .withInitScript("db/init-test.sql");

    public static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13.7-management-alpine");

    static {
        Startables.deepStart(POSTGRES, RABBIT).join();
    }

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName());
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("audit.test.expected-database", POSTGRES::getDatabaseName);
        properties.add("audit.test.expected-user", POSTGRES::getUsername);

        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        properties.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        properties.add("spring.rabbitmq.password", RABBIT::getAdminPassword);

        properties.add("audit.consumer.exchange", () -> TEST_EXCHANGE);
        properties.add("audit.consumer.queue", () -> TEST_QUEUE);
        properties.add("audit.consumer.routing-key", () -> TEST_ROUTING_KEY);
        properties.add("audit.consumer.retry.max-attempts", () -> "3");
        properties.add("audit.consumer.retry.initial-interval-ms", () -> "20");
        properties.add("audit.consumer.retry.multiplier", () -> "1.1");
        properties.add("audit.consumer.retry.max-interval-ms", () -> "40");
        properties.add("notifications.consumer.queue", () -> TEST_NOTIFICATION_QUEUE);
        properties.add("notifications.consumer.routing-key", () -> TEST_ROUTING_KEY);
        properties.add("notifications.consumer.retry.max-attempts", () -> "3");
        properties.add("notifications.consumer.retry.initial-interval-ms", () -> "20");
        properties.add("notifications.consumer.retry.multiplier", () -> "1.1");
        properties.add("notifications.consumer.retry.max-interval-ms", () -> "40");

        properties.add("audit.security.jwks-uri", () -> "http://127.0.0.1:1/.well-known/jwks.json");
        properties.add("audit.security.issuer", () -> "siga-identity");
        properties.add("audit.security.audience", () -> "siga-api");
    }
}
