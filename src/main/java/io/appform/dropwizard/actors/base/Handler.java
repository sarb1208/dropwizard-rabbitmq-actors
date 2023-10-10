package io.appform.dropwizard.actors.base;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import io.appform.dropwizard.actors.actor.MessageHandlingFunction;
import io.appform.dropwizard.actors.actor.MessageMetadata;
import io.appform.dropwizard.actors.exceptionhandler.handlers.ExceptionHandler;
import io.appform.dropwizard.actors.retry.RetryStrategy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.Function;

import static io.appform.dropwizard.actors.common.Constants.MESSAGE_EXPIRY_TEXT;
import static io.appform.dropwizard.actors.common.Constants.MESSAGE_PUBLISHED_TEXT;

@Slf4j
public class Handler<Message> extends DefaultConsumer {

    private final ObjectMapper mapper;
    private final Class<? extends Message> clazz;
    private final Function<Throwable, Boolean> errorCheckFunction;
    private final RetryStrategy retryStrategy;
    private final ExceptionHandler exceptionHandler;
    private final MessageHandlingFunction<Message, Boolean> messageHandlingFunction;
    private final MessageHandlingFunction<Message, Boolean> expiredMessageHandlingFunction;

    @Getter
    private volatile boolean running;

    @Getter
    @Setter
    private String tag;

    public Handler(final Channel channel,
                   final ObjectMapper mapper,
                   final Class<? extends Message> clazz,
                   final int prefetchCount,
                   final Function<Throwable, Boolean> errorCheckFunction,
                   final RetryStrategy retryStrategy,
                   final ExceptionHandler exceptionHandler,
                   final MessageHandlingFunction<Message, Boolean> messageHandlingFunction,
                   final MessageHandlingFunction<Message, Boolean> expiredMessageHandlingFunction) throws Exception {
        super(channel);
        this.mapper = mapper;
        this.clazz = clazz;
        getChannel().basicQos(prefetchCount);
        this.errorCheckFunction = errorCheckFunction;
        this.retryStrategy = retryStrategy;
        this.exceptionHandler = exceptionHandler;
        this.messageHandlingFunction = messageHandlingFunction;
        this.expiredMessageHandlingFunction = expiredMessageHandlingFunction;
    }

    private boolean handle(final Message message, final MessageMetadata messageMetadata, final boolean expired) throws Exception {
        running = true;
        try {
            return expired
                    ? expiredMessageHandlingFunction.apply(message, messageMetadata)
                    : messageHandlingFunction.apply(message, messageMetadata);
        } finally {
            running = false;
        }
    }

    @Override
    public void handleDelivery(final String consumerTag,
                               final Envelope envelope,
                               final AMQP.BasicProperties properties,
                               final byte[] body) throws IOException {
        try {
            val handleCallable = getHandleCallable(envelope, properties, body);

            if (retryStrategy.execute(handleCallable)) {
                getChannel().basicAck(envelope.getDeliveryTag(), false);
            } else {
                getChannel().basicReject(envelope.getDeliveryTag(), false);
            }
        } catch (Throwable t) {
            log.error("Error processing message...", t);
            if (errorCheckFunction.apply(t)) {
                log.warn("Acked message due to exception: ", t);
                getChannel().basicAck(envelope.getDeliveryTag(), false);
            } else if (exceptionHandler.handle()) {
                log.warn("Acked message due to exception handling strategy: ", t);
                getChannel().basicAck(envelope.getDeliveryTag(), false);
            } else {
                getChannel().basicReject(envelope.getDeliveryTag(), false);
            }
        }
    }

    private Callable<Boolean> getHandleCallable(final Envelope envelope,
                                                final AMQP.BasicProperties properties,
                                                final byte[] body) throws IOException {
        val delayInMs = getDelayInMs(properties);
        val expired = isExpired(properties);
        val message = mapper.readValue(body, clazz);
        return () -> handle(message, messageProperties(envelope, delayInMs), expired);
    }

    private long getDelayInMs(final AMQP.BasicProperties properties) {
        if (properties.getHeaders() != null
                && properties.getHeaders().containsKey(MESSAGE_PUBLISHED_TEXT)) {
            val publishedAt = (long) properties.getHeaders().get(MESSAGE_PUBLISHED_TEXT);
            return Math.max(Instant.now().toEpochMilli() - publishedAt, 0);
        } else {
            return -1;
        }
    }

    private boolean isExpired(final AMQP.BasicProperties properties) {
        if (properties.getHeaders() != null
                && properties.getHeaders().containsKey(MESSAGE_EXPIRY_TEXT)) {
            val expiresAt = (long) properties.getHeaders().get(MESSAGE_EXPIRY_TEXT);
            return Instant.now().toEpochMilli() >= expiresAt;
        }
        return false;
    }

    private MessageMetadata messageProperties(final Envelope envelope, final long messageDelay) {
        return new MessageMetadata(envelope.isRedeliver(), messageDelay);
    }
}