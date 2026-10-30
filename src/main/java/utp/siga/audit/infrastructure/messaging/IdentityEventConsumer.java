package utp.siga.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import utp.siga.audit.application.port.in.RecordIdentityAuditUseCase;

@Component
public class IdentityEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(IdentityEventConsumer.class);

    private final RecordIdentityAuditUseCase useCase;
    private final IdentityEventValidator validator;
    private final ObjectMapper mapper;
    private final RetryTemplate retry;

    public IdentityEventConsumer(RecordIdentityAuditUseCase useCase, IdentityEventValidator validator,
            ObjectMapper mapper, @Qualifier("identityAuditConsumerRetryTemplate") RetryTemplate retry) {
        this.useCase = useCase;
        this.validator = validator;
        this.mapper = mapper;
        this.retry = retry;
    }

    @RabbitListener(queues = "${audit.identity-consumer.queue:siga.identity.audit}",
            containerFactory = "auditEventListenerContainerFactory",
            concurrency = "1",
            autoStartup = "${audit.identity-consumer.enabled:false}")
    public void onEvent(Message message, Channel channel) {
        long tag = message.getMessageProperties().getDeliveryTag();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        ValidatedIdentityEvent event;
        try {
            JsonNode envelope = mapper.readTree(message.getBody());
            event = validator.validateAndExtract(routingKey, envelope);
        } catch (Exception exception) {
            log.warn("Rejecting invalid IAM event routingKey={}: {}", routingKey, exception.getMessage());
            reject(channel, tag, exception);
            return;
        }

        try {
            retry.execute(context -> useCase.record(event.eventId(), event.command()));
            channel.basicAck(tag, false);
        } catch (Exception exception) {
            log.error("IAM event processing exhausted retries eventId={}", event.eventId(), exception);
            reject(channel, tag, exception);
        }
    }

    private static void reject(Channel channel, long tag, Exception cause) {
        try {
            channel.basicReject(tag, false);
        } catch (IOException failure) {
            failure.addSuppressed(cause);
            throw new IllegalStateException("Could not reject IAM delivery", failure);
        }
    }
}
