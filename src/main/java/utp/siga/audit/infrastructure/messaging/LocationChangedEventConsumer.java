package utp.siga.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import utp.siga.audit.application.command.RecordLocationAuditCommand;
import utp.siga.audit.application.port.in.RecordLocationAuditUseCase;

@Component
public class LocationChangedEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(LocationChangedEventConsumer.class);
    private final RecordLocationAuditUseCase useCase;
    private final LocationChangedEventValidator validator;
    private final ObjectMapper mapper;
    private final RetryTemplate retry;

    public LocationChangedEventConsumer(RecordLocationAuditUseCase useCase,
            LocationChangedEventValidator validator, ObjectMapper mapper,
            @Qualifier("auditConsumerRetryTemplate") RetryTemplate retry) {
        this.useCase = useCase;
        this.validator = validator;
        this.mapper = mapper;
        this.retry = retry;
    }

    @RabbitListener(queues = "${audit.location-consumer.queue:audit.location.changed.v1}",
            containerFactory = "auditEventListenerContainerFactory",
            autoStartup = "${audit.location-consumer.enabled:false}")
    public void onEvent(Message message, Channel channel) {
        long tag = message.getMessageProperties().getDeliveryTag();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        UUID eventId;
        RecordLocationAuditCommand command;
        try {
            JsonNode envelope = mapper.readTree(message.getBody());
            command = validator.validateAndExtract(routingKey, envelope);
            eventId = UUID.fromString(envelope.path("eventId").asText());
        } catch (Exception exception) {
            reject(channel, tag, exception);
            return;
        }
        try {
            retry.execute(context -> useCase.recordLocationChanged(eventId, command));
            channel.basicAck(tag, false);
        } catch (Exception exception) {
            log.error("LocationChanged processing exhausted retries eventId={}", eventId, exception);
            reject(channel, tag, exception);
        }
    }

    private static void reject(Channel channel, long tag, Exception cause) {
        try { channel.basicReject(tag, false); }
        catch (IOException failure) { failure.addSuppressed(cause); throw new IllegalStateException(failure); }
    }
}
