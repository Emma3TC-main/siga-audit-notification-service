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
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import utp.siga.audit.application.command.RecordMovementNotificationCommand;
import utp.siga.audit.application.port.in.NotificationUseCase;

@Component
public class MovementConfirmedNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(MovementConfirmedNotificationConsumer.class);

    private final NotificationUseCase notifications;
    private final MovementConfirmedNotificationValidator validator;
    private final ObjectMapper objectMapper;
    private final RetryTemplate retryTemplate;

    public MovementConfirmedNotificationConsumer(
            NotificationUseCase notifications,
            MovementConfirmedNotificationValidator validator,
            ObjectMapper objectMapper,
            @Qualifier("notificationConsumerRetryTemplate") RetryTemplate retryTemplate) {
        this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.retryTemplate = Objects.requireNonNull(retryTemplate, "retryTemplate must not be null");
    }

    @RabbitListener(
            queues = "${notifications.consumer.queue:notifications.movement.confirmed.v1}",
            containerFactory = "auditEventListenerContainerFactory",
            autoStartup = "${notifications.consumer.enabled:false}")
    public void onEvent(Message message, Channel channel) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        UUID eventId;
        RecordMovementNotificationCommand command;
        try {
            JsonNode envelope = objectMapper.readTree(message.getBody());
            command = validator.validateAndExtract(routingKey, envelope);
            eventId = UUID.fromString(envelope.path("eventId").asText());
            log.info("Received notification event {} with correlationId={}",
                    eventId, envelope.path("correlationId").asText());
        } catch (Exception ex) {
            log.warn("Rejecting invalid notification event on routing key {}: {}", routingKey, ex.getMessage());
            reject(channel, deliveryTag, ex);
            return;
        }

        try {
            boolean created = retryTemplate.execute(context ->
                    notifications.recordMovementConfirmed(eventId, command));
            if (!created) {
                log.info("Duplicate notification event {} ignored", eventId);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.error("Notification processing exhausted retries for event {}", eventId, ex);
            reject(channel, deliveryTag, ex);
        }
    }

    private static void reject(Channel channel, long deliveryTag, Exception cause) {
        try {
            channel.basicReject(deliveryTag, false);
        } catch (IOException rejectFailure) {
            rejectFailure.addSuppressed(cause);
            throw new IllegalStateException("Could not reject RabbitMQ notification delivery", rejectFailure);
        }
    }
}
