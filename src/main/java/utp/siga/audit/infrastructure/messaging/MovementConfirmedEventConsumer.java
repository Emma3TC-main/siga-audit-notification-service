package utp.siga.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.retry.support.RetryTemplate;
import utp.siga.audit.application.command.RecordAuditEventCommand;
import utp.siga.audit.application.port.in.RecordAuditEventUseCase;

@Component
public class MovementConfirmedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(MovementConfirmedEventConsumer.class);

    private final RecordAuditEventUseCase recordAuditEventUseCase;
    private final MovementConfirmedEventValidator validator;
    private final ObjectMapper objectMapper;
    private final RetryTemplate retryTemplate;

    public MovementConfirmedEventConsumer(
            RecordAuditEventUseCase recordAuditEventUseCase,
            MovementConfirmedEventValidator validator,
            ObjectMapper objectMapper,
            @Qualifier("auditConsumerRetryTemplate") RetryTemplate retryTemplate) {
        this.recordAuditEventUseCase = Objects.requireNonNull(recordAuditEventUseCase, "recordAuditEventUseCase must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.retryTemplate = Objects.requireNonNull(retryTemplate, "retryTemplate must not be null");
    }

    @RabbitListener(
            queues = "${audit.consumer.queue:audit.movement.confirmed.v1}",
            containerFactory = "auditEventListenerContainerFactory",
            autoStartup = "${audit.consumer.enabled:false}")
    public void onEvent(Message message, Channel channel) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        JsonNode envelope;
        RecordAuditEventCommand command;
        UUID eventId;
        try {
            envelope = objectMapper.readTree(message.getBody());
            command = validator.validateAndExtract(routingKey, envelope);
            eventId = UUID.fromString(envelope.get("eventId").asText());
            log.info("Received valid event {} on routing key {} with correlationId={}",
                    eventId, routingKey, command.correlationId());
        } catch (Exception ex) {
            log.warn("Rejecting invalid event on routing key {}: {}", routingKey, ex.getMessage());
            reject(channel, deliveryTag, ex);
            return;
        }

        try {
            boolean recorded = retryTemplate.execute(context ->
                    recordAuditEventUseCase.recordMovementConfirmed(eventId, command));
            if (!recorded) {
                log.info("Duplicate event {} ignored with zero duplicate effect", eventId);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.error("Processing exhausted retries; rejecting event {} on routing key {}", eventId, routingKey, ex);
            reject(channel, deliveryTag, ex);
        }
    }

    private static void reject(Channel channel, long deliveryTag, Exception cause) {
        try {
            channel.basicReject(deliveryTag, false);
        } catch (IOException rejectFailure) {
            rejectFailure.addSuppressed(cause);
            throw new IllegalStateException("Could not reject RabbitMQ delivery", rejectFailure);
        }
    }
}
