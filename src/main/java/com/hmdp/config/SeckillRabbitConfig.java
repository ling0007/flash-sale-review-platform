package com.hmdp.config;

import com.hmdp.utils.SeckillMqConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SeckillRabbitConfig {
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SeckillMqConstants.EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillOrderQueue() {
        return new Queue(SeckillMqConstants.QUEUE, true, false, false);
    }

    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, DirectExchange seckillExchange) {
        return BindingBuilder.bind(seckillOrderQueue).to(seckillExchange)
                .with(SeckillMqConstants.ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter("com.hmdp.dto");
    }

    @Bean
    public SmartInitializingSingleton declareSeckillTopology(AmqpAdmin admin,
            DirectExchange seckillExchange, Queue seckillOrderQueue, Binding seckillOrderBinding) {
        // Declare before listeners start; connection/authentication/declaration errors abort startup.
        return () -> {
            admin.declareExchange(seckillExchange);
            admin.declareQueue(seckillOrderQueue);
            admin.declareBinding(seckillOrderBinding);
        };
    }
}
