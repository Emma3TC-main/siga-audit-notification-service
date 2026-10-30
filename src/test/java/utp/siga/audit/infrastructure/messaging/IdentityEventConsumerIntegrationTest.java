package utp.siga.audit.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import utp.siga.audit.AuditIntegrationEnvironment;
import utp.siga.audit.application.command.RecordIdentityAuditCommand;
import utp.siga.audit.application.usecase.RecordIdentityAuditService;
import utp.siga.audit.infrastructure.persistence.PostgresAuditEventRepository;

@SpringBootTest(properties = {
        "audit.identity-consumer.enabled=true",
        "audit.identity-consumer.queue=test.identity.audit",
        "audit.identity-consumer.dead-letter-exchange=test.identity.audit.dlx",
        "audit.identity-consumer.dead-letter-queue=test.identity.audit.dlq",
        "audit.identity-consumer.dead-letter-routing-key=test.identity.audit.dead",
        "audit.identity-consumer.retry.max-attempts=3",
        "audit.identity-consumer.retry.initial-interval-ms=10",
        "audit.identity-consumer.retry.multiplier=1.1",
        "audit.identity-consumer.retry.max-interval-ms=20"
})
@Import(IdentityEventConsumerIntegrationTest.IdentityQueueConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdentityEventConsumerIntegrationTest extends AuditIntegrationEnvironment {
    private static final String QUEUE = "test.identity.audit";
    private static final String DLQ = "test.identity.audit.dlq";

    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired RecordIdentityAuditService service;
    @SpyBean RecordIdentityAuditService serviceSpy;
    @SpyBean PostgresAuditEventRepository eventRepository;

    private final List<UUID> eventIds = new ArrayList<>();
    private final List<UUID> aggregateIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        drain(QUEUE);
        drain(DLQ);
        reset(serviceSpy, eventRepository);
    }

    @AfterEach
    void cleanOwnedRows() {
        aggregateIds.forEach(id -> jdbc.update("DELETE FROM audit.audit_event WHERE aggregate_id=?", id));
        eventIds.forEach(id -> jdbc.update("DELETE FROM audit.processed_event WHERE event_id=?", id));
        eventIds.clear();
        aggregateIds.clear();
        drain(QUEUE);
        drain(DLQ);
    }

    @Test
    void consumesAllV1AndV2ActionsAndCreatesNoNotifications() throws Exception {
        List<ContractCase> cases = List.of(
                new ContractCase("iam.UserCreated", "UserCreated", 1, "Identity", "USER_CREATED"),
                new ContractCase("iam.UserUpdated", "UserUpdated", 1, "Identity", "USER_UPDATED"),
                new ContractCase("iam.UserDisabled", "UserDisabled", 1, "Identity", "USER_DISABLED"),
                new ContractCase("iam.RoleChanged", "RoleChanged", 1, "Identity", "IDENTITY_ROLE_CHANGED_LEGACY"),
                new ContractCase("iam.user.created.v2", "UserCreated", 2, "User", "USER_CREATED"),
                new ContractCase("iam.user.updated.v2", "UserUpdated", 2, "User", "USER_UPDATED"),
                new ContractCase("iam.user.disabled.v2", "UserDisabled", 2, "User", "USER_DISABLED"),
                new ContractCase("iam.user.roles.changed.v2", "UserRolesChanged", 2, "User", "USER_ROLES_CHANGED"),
                new ContractCase("iam.role.created.v2", "RoleCreated", 2, "Role", "ROLE_CREATED"),
                new ContractCase("iam.role.permissions.changed.v2", "RolePermissionsChanged", 2, "Role",
                        "ROLE_PERMISSIONS_CHANGED"));

        for (ContractCase contract : cases) {
            UUID eventId = ownedEvent();
            UUID aggregateId = ownedAggregate();
            publish(contract.routingKey(), envelope(eventId, contract.eventType(), contract.version(),
                    contract.aggregateType(), aggregateId));
            awaitProcessed(eventId);
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT event_type,aggregate_type,action,result,correlation_id,details::text details "
                            + "FROM audit.audit_event WHERE aggregate_id=?", aggregateId);
            assertThat(row.get("event_type")).isEqualTo(contract.eventType());
            assertThat(row.get("aggregate_type")).isEqualTo(contract.aggregateType());
            assertThat(row.get("action")).isEqualTo(contract.action());
            assertThat(row.get("result")).isEqualTo("SUCCESS");
            assertThat(row.get("correlation_id")).isNotNull();
            String details = row.get("details").toString();
            assertThat(details).doesNotContainIgnoringCase("password")
                    .doesNotContainIgnoringCase("token")
                    .doesNotContainIgnoringCase("otp");
            if (contract.version() == 1) assertThat(details).contains("legacy");
            else assertThat(details).contains("before", "after");
        }

        assertThat(aggregateIds).allSatisfy(id -> assertThat(count(
                "SELECT count(*) FROM audit.notification WHERE reference_id=?", id)).isZero());
    }

    @Test
    void duplicateV1AndV2DeliveriesProduceOneAuditAndOneClaim() throws Exception {
        for (ContractCase contract : List.of(
                new ContractCase("iam.RoleChanged", "RoleChanged", 1, "Identity", "IDENTITY_ROLE_CHANGED_LEGACY"),
                new ContractCase("iam.user.roles.changed.v2", "UserRolesChanged", 2, "User", "USER_ROLES_CHANGED"))) {
            UUID eventId = ownedEvent();
            UUID aggregateId = ownedAggregate();
            ObjectNode envelope = envelope(eventId, contract.eventType(), contract.version(),
                    contract.aggregateType(), aggregateId);
            publish(contract.routingKey(), envelope);
            awaitProcessed(eventId);
            publish(contract.routingKey(), envelope);
        }
        Thread.sleep(300);
        assertThat(eventIds).allSatisfy(eventId -> assertThat(count(
                "SELECT count(*) FROM audit.processed_event WHERE event_id=? AND consumer=?",
                eventId, RecordIdentityAuditService.CONSUMER)).isOne());
        assertThat(aggregateIds).allSatisfy(id -> assertThat(count(
                "SELECT count(*) FROM audit.audit_event WHERE aggregate_id=?", id)).isOne());
    }

    @Test
    void malformedUnsupportedAndSensitiveMessagesReachDlqWithoutDatabaseEffect() throws Exception {
        List<RunnableMessage> invalid = new ArrayList<>();
        invalid.add(new RunnableMessage("iam.unknown.v2", envelope(ownedEvent(), "UserCreated", 2,
                "User", ownedAggregate())));

        ObjectNode mismatch = envelope(ownedEvent(), "UserCreated", 2, "User", ownedAggregate());
        mismatch.put("eventType", "UserUpdated");
        invalid.add(new RunnableMessage("iam.user.created.v2", mismatch));

        ObjectNode schema = envelope(ownedEvent(), "UserCreated", 1, "Identity", ownedAggregate());
        schema.put("schemaVersion", 3);
        invalid.add(new RunnableMessage("iam.UserCreated", schema));

        ObjectNode producer = envelope(ownedEvent(), "UserCreated", 1, "Identity", ownedAggregate());
        producer.put("producer", "unknown-service");
        invalid.add(new RunnableMessage("iam.UserCreated", producer));

        ObjectNode aggregateType = envelope(ownedEvent(), "UserCreated", 2, "User", ownedAggregate());
        aggregateType.put("aggregateType", "Identity");
        invalid.add(new RunnableMessage("iam.user.created.v2", aggregateType));

        ObjectNode aggregateMismatch = envelope(ownedEvent(), "UserUpdated", 2, "User", ownedAggregate());
        aggregateMismatch.withObject("/payload").put("userId", UUID.randomUUID().toString());
        invalid.add(new RunnableMessage("iam.user.updated.v2", aggregateMismatch));

        ObjectNode sensitive = envelope(ownedEvent(), "UserUpdated", 2, "User", ownedAggregate());
        sensitive.withObject("/payload/after").put("passwordHash", "forbidden");
        invalid.add(new RunnableMessage("iam.user.updated.v2", sensitive));

        for (RunnableMessage value : invalid) publish(value.routingKey(), value.envelope());
        awaitDlq(invalid.size());
        assertThat(eventIds).allSatisfy(id -> assertThat(count(
                "SELECT count(*) FROM audit.processed_event WHERE event_id=?", id)).isZero());
        assertThat(aggregateIds).allSatisfy(id -> assertThat(count(
                "SELECT count(*) FROM audit.audit_event WHERE aggregate_id=?", id)).isZero());
    }

    @Test
    void transientFailureRetriesThenRecoversWithStableEventId() throws Exception {
        UUID eventId = ownedEvent();
        UUID aggregateId = ownedAggregate();
        doThrow(new IllegalStateException("temporary failure")).doCallRealMethod()
                .when(serviceSpy).record(eq(eventId), any());

        publish("iam.user.updated.v2", envelope(eventId, "UserUpdated", 2, "User", aggregateId));
        awaitProcessed(eventId);

        verify(serviceSpy, timeout(5000).times(2)).record(eq(eventId), any());
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE aggregate_id=?", aggregateId)).isOne();
        assertThat(receive(DLQ, 100)).isNull();
    }

    @Test
    void exhaustedFailureIsDeadLetteredWithoutClaimOrAudit() throws Exception {
        UUID eventId = ownedEvent();
        UUID aggregateId = ownedAggregate();
        doThrow(new IllegalStateException("persistent failure"))
                .when(serviceSpy).record(eq(eventId), any());

        publish("iam.user.updated.v2", envelope(eventId, "UserUpdated", 2, "User", aggregateId));
        awaitDlq(1);

        verify(serviceSpy, timeout(5000).times(3)).record(eq(eventId), any());
        assertThat(count("SELECT count(*) FROM audit.processed_event WHERE event_id=?", eventId)).isZero();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE aggregate_id=?", aggregateId)).isZero();
    }

    @Test
    void auditInsertFailureRollsBackProcessedClaim() {
        UUID eventId = ownedEvent();
        UUID aggregateId = ownedAggregate();
        doThrow(new IllegalStateException("audit insert failed")).when(eventRepository).save(any());
        RecordIdentityAuditCommand command = new RecordIdentityAuditCommand("UserUpdated", UUID.randomUUID(),
                "User", aggregateId, "USER_UPDATED", UUID.randomUUID(), Instant.parse("2026-10-30T12:00:00Z"),
                Map.of("before", Map.of("active", true), "after", Map.of("active", false)));

        assertThatThrownBy(() -> service.record(eventId, command))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT count(*) FROM audit.processed_event WHERE event_id=?", eventId)).isZero();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE aggregate_id=?", aggregateId)).isZero();
    }

    private ObjectNode envelope(UUID eventId, String eventType, int version, String aggregateType,
            UUID aggregateId) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", version);
        envelope.put("producer", "identity-service");
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId.toString());
        envelope.put("correlationId", UUID.randomUUID().toString());
        envelope.put("occurredAt", "2026-10-30T12:00:00Z");
        ObjectNode payload = envelope.putObject("payload");
        if (version == 1) {
            payload.put("id", aggregateId.toString());
            payload.putNull("actorId");
            return envelope;
        }
        boolean user = "User".equals(aggregateType);
        payload.put(user ? "userId" : "roleId", aggregateId.toString());
        payload.put("actorUserId", UUID.randomUUID().toString());
        if (eventType.endsWith("Created")) payload.putNull("before");
        else payload.set("before", user ? userSnapshot(aggregateId, true) : roleSnapshot(aggregateId));
        payload.set("after", user ? userSnapshot(aggregateId, !"UserDisabled".equals(eventType))
                : roleSnapshot(aggregateId));
        return envelope;
    }

    private ObjectNode userSnapshot(UUID id, boolean active) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id.toString());
        node.put("username", "identity.audit");
        node.put("email", "identity.audit@example.test");
        node.put("active", active);
        node.put("mfaEnabled", true);
        node.putArray("roleIds").add(UUID.randomUUID().toString());
        return node;
    }

    private ObjectNode roleSnapshot(UUID id) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id.toString());
        node.put("code", "ROLE_TEST");
        node.put("name", "Role Test");
        node.put("active", true);
        node.putArray("permissionIds").add(UUID.randomUUID().toString());
        return node;
    }

    private void publish(String routingKey, ObjectNode envelope) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbit.send(TEST_EXCHANGE, routingKey,
                new Message(envelope.toString().getBytes(StandardCharsets.UTF_8), properties));
    }

    private void awaitProcessed(UUID eventId) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (count("SELECT count(*) FROM audit.processed_event WHERE event_id=? AND consumer=?",
                    eventId, RecordIdentityAuditService.CONSUMER) == 1) return;
            Thread.sleep(40);
        }
        assertThat(count("SELECT count(*) FROM audit.processed_event WHERE event_id=? AND consumer=?",
                eventId, RecordIdentityAuditService.CONSUMER)).isOne();
    }

    private void awaitDlq(int expected) throws Exception {
        int received = 0;
        long deadline = System.currentTimeMillis() + 8000;
        while (received < expected && System.currentTimeMillis() < deadline) {
            if (receive(DLQ, 100) != null) received++;
        }
        assertThat(received).isEqualTo(expected);
    }

    private Message receive(String queue, long timeout) {
        return rabbit.receive(queue, timeout);
    }

    private void drain(String queue) {
        while (receive(queue, 20) != null) { }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private UUID ownedEvent() { UUID id = UUID.randomUUID(); eventIds.add(id); return id; }
    private UUID ownedAggregate() { UUID id = UUID.randomUUID(); aggregateIds.add(id); return id; }

    private record ContractCase(String routingKey, String eventType, int version,
            String aggregateType, String action) { }
    private record RunnableMessage(String routingKey, ObjectNode envelope) { }

    @TestConfiguration
    static class IdentityQueueConfiguration {
        @Bean("identityAuditTestSourceQueue")
        Queue identityAuditTestSourceQueue() {
            return QueueBuilder.durable(QUEUE)
                    .deadLetterExchange("test.identity.audit.dlx")
                    .deadLetterRoutingKey("test.identity.audit.dead")
                    .build();
        }

        @Bean
        Binding identityAuditTestSourceBinding(
                @Qualifier("identityAuditTestSourceQueue") Queue identityAuditTestSourceQueue,
                TopicExchange eventsExchange) {
            return BindingBuilder.bind(identityAuditTestSourceQueue).to(eventsExchange).with("iam.#");
        }
    }
}
