package com.hmdp.config;

import com.hmdp.utils.SeckillMqConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SeckillRabbitConfig {
    @Bean
    public DirectExchange seckillErrorExchange() {
        return new DirectExchange(SeckillMqConstants.ERROR_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillErrorQueue() {
        return new Queue(SeckillMqConstants.ERROR_QUEUE, true, false, false);
    }

    @Bean
    public Binding seckillErrorBinding(Queue seckillErrorQueue, DirectExchange seckillErrorExchange) {
        return BindingBuilder.bind(seckillErrorQueue).to(seckillErrorExchange)
                .with(SeckillMqConstants.ERROR_ROUTING_KEY);
    }

    @Bean
    public RepublishMessageRecoverer seckillMessageRecoverer(RabbitTemplate rabbitTemplate) {
        // Boot 将该 Recoverer 接入 Consumer Retry。保留原始消息体并添加异常栈、原交换机及路由头。
        // 转发调用正常返回后，原消息才由 AUTO ACK 确认；此阶段不启用发布确认。
        RepublishMessageRecoverer recoverer = new RepublishMessageRecoverer(rabbitTemplate,
                SeckillMqConstants.ERROR_EXCHANGE, SeckillMqConstants.ERROR_ROUTING_KEY);
        recoverer.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return recoverer;
    }

    @Bean
    public SmartInitializingSingleton declareSeckillErrorTopology(AmqpAdmin admin,
            DirectExchange seckillErrorExchange, Queue seckillErrorQueue, Binding seckillErrorBinding) {
        // 启动时同时检查错误拓扑，避免已知的连接/声明错误被留到重试耗尽时才发现。
        return () -> {
            admin.declareExchange(seckillErrorExchange);
            admin.declareQueue(seckillErrorQueue);
            admin.declareBinding(seckillErrorBinding);
        };
    }

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
        // Listener 启动前声明拓扑；连接、认证、声明错误均终止启动。
        return () -> {
            admin.declareExchange(seckillExchange);
            admin.declareQueue(seckillOrderQueue);
            admin.declareBinding(seckillOrderBinding);
        };
    }
}
