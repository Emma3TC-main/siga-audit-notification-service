package utp.siga.audit.infrastructure.messaging;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class RabbitMQConfiguration {

    @Bean("identityAuditDeadLetterExchange")
    @ConditionalOnProperty(name = "audit.identity-consumer.enabled", havingValue = "true")
    public DirectExchange identityAuditDeadLetterExchange(
            @Value("${audit.identity-consumer.dead-letter-exchange:siga.identity.audit.dlx}") String exchange) {
        return new DirectExchange(exchange, true, false);
    }

    @Bean("identityAuditDeadLetterQueue")
    @ConditionalOnProperty(name = "audit.identity-consumer.enabled", havingValue = "true")
    public Queue identityAuditDeadLetterQueue(
            @Value("${audit.identity-consumer.dead-letter-queue:siga.identity.audit.dlq}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    @ConditionalOnProperty(name = "audit.identity-consumer.enabled", havingValue = "true")
    public Binding identityAuditDeadLetterBinding(
            @Qualifier("identityAuditDeadLetterQueue") Queue queue,
            @Qualifier("identityAuditDeadLetterExchange") DirectExchange exchange,
            @Value("${audit.identity-consumer.dead-letter-routing-key:siga.identity.audit.dead}") String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }

    @Bean("identityAuditConsumerRetryTemplate")
    public RetryTemplate identityAuditConsumerRetryTemplate(
            @Value("${audit.identity-consumer.retry.max-attempts:3}") int maxAttempts,
            @Value("${audit.identity-consumer.retry.initial-interval-ms:250}") long initialInterval,
            @Value("${audit.identity-consumer.retry.multiplier:2.0}") double multiplier,
            @Value("${audit.identity-consumer.retry.max-interval-ms:2000}") long maxInterval) {
        return RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .exponentialBackoff(initialInterval, multiplier, maxInterval)
                .build();
    }

    @Bean
    public TopicExchange eventsExchange(@Value("${audit.consumer.exchange:siga.events}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Queue auditMovementConfirmedQueue(@Value("${audit.consumer.queue:audit.movement.confirmed.v1}") String queue) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(queue + ".dlx")
                .deadLetterRoutingKey(queue + ".dead")
                .build();
    }

    @Bean
    public DirectExchange auditDeadLetterExchange(@Value("${audit.consumer.queue:audit.movement.confirmed.v1}") String queue) {
        return new DirectExchange(queue + ".dlx", true, false);
    }

    @Bean
    public Queue auditDeadLetterQueue(@Value("${audit.consumer.queue:audit.movement.confirmed.v1}") String queue) {
        return QueueBuilder.durable(queue + ".dlq").build();
    }

    @Bean
    public Binding auditDeadLetterBinding(
            @Qualifier("auditDeadLetterQueue") Queue deadLetterQueue,
            @Qualifier("auditDeadLetterExchange") DirectExchange auditDeadLetterExchange,
            @Value("${audit.consumer.queue:audit.movement.confirmed.v1}") String queue) {
        return BindingBuilder.bind(deadLetterQueue).to(auditDeadLetterExchange).with(queue + ".dead");
    }

    @Bean
    public Binding auditMovementConfirmedBinding(
            @Qualifier("auditMovementConfirmedQueue") Queue queue,
            TopicExchange eventsExchange,
            @Value("${audit.consumer.routing-key:inventory.movement.confirmed.v1}") String routingKey) {
        return BindingBuilder.bind(queue).to(eventsExchange).with(routingKey);
    }

    @Bean
    public Queue auditLocationChangedQueue(
            @Value("${audit.location-consumer.queue:audit.location.changed.v1}") String queue) {
        return QueueBuilder.durable(queue).deadLetterExchange(queue + ".dlx")
                .deadLetterRoutingKey(queue + ".dead").build();
    }

    @Bean
    public DirectExchange auditLocationDeadLetterExchange(
            @Value("${audit.location-consumer.queue:audit.location.changed.v1}") String queue) {
        return new DirectExchange(queue + ".dlx", true, false);
    }

    @Bean
    public Queue auditLocationDeadLetterQueue(
            @Value("${audit.location-consumer.queue:audit.location.changed.v1}") String queue) {
        return QueueBuilder.durable(queue + ".dlq").build();
    }

    @Bean
    public Binding auditLocationDeadLetterBinding(
            @Qualifier("auditLocationDeadLetterQueue") Queue deadLetterQueue,
            @Qualifier("auditLocationDeadLetterExchange") DirectExchange deadLetterExchange,
            @Value("${audit.location-consumer.queue:audit.location.changed.v1}") String queue) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(queue + ".dead");
    }

    @Bean
    public Binding auditLocationChangedBinding(@Qualifier("auditLocationChangedQueue") Queue queue,
            TopicExchange eventsExchange,
            @Value("${audit.location-consumer.routing-key:inventory.location.changed.v1}") String routingKey) {
        return BindingBuilder.bind(queue).to(eventsExchange).with(routingKey);
    }

    @Bean
    public Queue auditLotChangedQueue(@Value("${audit.lot-consumer.queue:audit.lot.changed.v1}") String queue) {
        return QueueBuilder.durable(queue).deadLetterExchange(queue + ".dlx")
                .deadLetterRoutingKey(queue + ".dead").build();
    }

    @Bean
    public DirectExchange auditLotDeadLetterExchange(
            @Value("${audit.lot-consumer.queue:audit.lot.changed.v1}") String queue) {
        return new DirectExchange(queue + ".dlx", true, false);
    }

    @Bean
    public Queue auditLotDeadLetterQueue(@Value("${audit.lot-consumer.queue:audit.lot.changed.v1}") String queue) {
        return QueueBuilder.durable(queue + ".dlq").build();
    }

    @Bean
    public Binding auditLotDeadLetterBinding(@Qualifier("auditLotDeadLetterQueue") Queue deadLetterQueue,
            @Qualifier("auditLotDeadLetterExchange") DirectExchange deadLetterExchange,
            @Value("${audit.lot-consumer.queue:audit.lot.changed.v1}") String queue) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(queue + ".dead");
    }

    @Bean
    public Binding auditLotChangedBinding(@Qualifier("auditLotChangedQueue") Queue queue,
            TopicExchange eventsExchange,
            @Value("${audit.lot-consumer.routing-key:inventory.lot.changed.v1}") String routingKey) {
        return BindingBuilder.bind(queue).to(eventsExchange).with(routingKey);
    }

    @Bean
    public Queue auditAssetChangedQueue(@Value("${audit.asset-consumer.queue:audit.asset.changed.v1}") String queue) {
        return QueueBuilder.durable(queue).deadLetterExchange(queue + ".dlx")
                .deadLetterRoutingKey(queue + ".dead").build();
    }

    @Bean
    public DirectExchange auditAssetDeadLetterExchange(
            @Value("${audit.asset-consumer.queue:audit.asset.changed.v1}") String queue) {
        return new DirectExchange(queue + ".dlx", true, false);
    }

    @Bean
    public Queue auditAssetDeadLetterQueue(
            @Value("${audit.asset-consumer.queue:audit.asset.changed.v1}") String queue) {
        return QueueBuilder.durable(queue + ".dlq").build();
    }

    @Bean
    public Binding auditAssetDeadLetterBinding(@Qualifier("auditAssetDeadLetterQueue") Queue deadLetterQueue,
            @Qualifier("auditAssetDeadLetterExchange") DirectExchange deadLetterExchange,
            @Value("${audit.asset-consumer.queue:audit.asset.changed.v1}") String queue) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(queue + ".dead");
    }

    @Bean
    public Binding auditAssetChangedBinding(@Qualifier("auditAssetChangedQueue") Queue queue,
            TopicExchange eventsExchange,
            @Value("${audit.asset-consumer.routing-key:inventory.asset.changed.v1}") String routingKey) {
        return BindingBuilder.bind(queue).to(eventsExchange).with(routingKey);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory auditEventListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(2);

        return factory;
    }

    @Bean("auditConsumerRetryTemplate")
    public RetryTemplate auditConsumerRetryTemplate(
            @Value("${audit.consumer.retry.max-attempts:3}") int maxAttempts,
            @Value("${audit.consumer.retry.initial-interval-ms:250}") long initialInterval,
            @Value("${audit.consumer.retry.multiplier:2.0}") double multiplier,
            @Value("${audit.consumer.retry.max-interval-ms:2000}") long maxInterval) {
        return RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .exponentialBackoff(initialInterval, multiplier, maxInterval)
                .build();
    }

    @Bean
    public Queue notificationMovementConfirmedQueue(
            @Value("${notifications.consumer.queue:notifications.movement.confirmed.v1}") String queue) {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(queue + ".dlx")
                .deadLetterRoutingKey(queue + ".dead")
                .build();
    }

    @Bean
    public DirectExchange notificationDeadLetterExchange(
            @Value("${notifications.consumer.queue:notifications.movement.confirmed.v1}") String queue) {
        return new DirectExchange(queue + ".dlx", true, false);
    }

    @Bean
    public Queue notificationDeadLetterQueue(
            @Value("${notifications.consumer.queue:notifications.movement.confirmed.v1}") String queue) {
        return QueueBuilder.durable(queue + ".dlq").build();
    }

    @Bean
    public Binding notificationDeadLetterBinding(
            @Qualifier("notificationDeadLetterQueue") Queue deadLetterQueue,
            @Qualifier("notificationDeadLetterExchange") DirectExchange deadLetterExchange,
            @Value("${notifications.consumer.queue:notifications.movement.confirmed.v1}") String queue) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(queue + ".dead");
    }

    @Bean
    public Binding notificationMovementConfirmedBinding(
            @Qualifier("notificationMovementConfirmedQueue") Queue queue,
            TopicExchange eventsExchange,
            @Value("${notifications.consumer.routing-key:inventory.movement.confirmed.v1}") String routingKey) {
        return BindingBuilder.bind(queue).to(eventsExchange).with(routingKey);
    }

    @Bean("notificationConsumerRetryTemplate")
    public RetryTemplate notificationConsumerRetryTemplate(
            @Value("${notifications.consumer.retry.max-attempts:3}") int maxAttempts,
            @Value("${notifications.consumer.retry.initial-interval-ms:250}") long initialInterval,
            @Value("${notifications.consumer.retry.multiplier:2.0}") double multiplier,
            @Value("${notifications.consumer.retry.max-interval-ms:2000}") long maxInterval) {
        return RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .exponentialBackoff(initialInterval, multiplier, maxInterval)
                .build();
    }
}
