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
import utp.siga.audit.application.command.RecordTraceabilityAuditCommand;
import utp.siga.audit.application.port.in.RecordTraceabilityAuditUseCase;
import utp.siga.audit.application.usecase.RecordTraceabilityAuditService;

@Component
public class LotChangedEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(LotChangedEventConsumer.class);
    private final RecordTraceabilityAuditUseCase useCase;
    private final LotChangedEventValidator validator;
    private final ObjectMapper mapper;
    private final RetryTemplate retry;

    public LotChangedEventConsumer(RecordTraceabilityAuditUseCase useCase, LotChangedEventValidator validator,
            ObjectMapper mapper, @Qualifier("auditConsumerRetryTemplate") RetryTemplate retry) {
        this.useCase = useCase;
        this.validator = validator;
        this.mapper = mapper;
        this.retry = retry;
    }

    @RabbitListener(queues = "${audit.lot-consumer.queue:audit.lot.changed.v1}",
            containerFactory = "auditEventListenerContainerFactory",
            autoStartup = "${audit.lot-consumer.enabled:false}")
    public void onEvent(Message message, Channel channel) {
        long tag = message.getMessageProperties().getDeliveryTag();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        UUID eventId;
        RecordTraceabilityAuditCommand command;
        try {
            JsonNode envelope = mapper.readTree(message.getBody());
            command = validator.validateAndExtract(routingKey, envelope);
            eventId = UUID.fromString(envelope.path("eventId").asText());
        } catch (Exception exception) {
            log.warn("Rejecting invalid LotChanged event on routingKey={}: {}", routingKey, exception.getMessage());
            reject(channel, tag, exception);
            return;
        }
        try {
            retry.execute(context -> useCase.record(eventId, RecordTraceabilityAuditService.LOT_CONSUMER, command));
            channel.basicAck(tag, false);
        } catch (Exception exception) {
            log.error("LotChanged processing exhausted retries eventId={}", eventId, exception);
            reject(channel, tag, exception);
        }
    }

    private static void reject(Channel channel, long tag, Exception cause) {
        try {
            channel.basicReject(tag, false);
        } catch (IOException failure) {
            failure.addSuppressed(cause);
            throw new IllegalStateException("Could not reject LotChanged delivery", failure);
        }
    }
}
