package com.hmdp;

import com.hmdp.config.SeckillRabbitConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.listener.VoucherOrderListener;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SeckillMqConstants;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ConnectException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillMigrationTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final RedisIdWorker ids = mock(RedisIdWorker.class);
    private final VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "rabbitTemplate", rabbit);
        ReflectionTestUtils.setField(service, "redisIdWorker", ids);
        UserDTO user = new UserDTO();
        user.setId(42L);
        UserHolder.saveUser(user);
        when(ids.nextId("order")).thenReturn(99L);
    }

    @AfterEach
    void clearUser() { UserHolder.removeUser(); }

    @SuppressWarnings("unchecked")
    private void luaReturns(Long result) {
        when(redis.execute(any(RedisScript.class), eq(Collections.emptyList()), eq("7"), eq("42")))
                .thenReturn(result);
    }

    @Test
    void successSendsSameIdentifiersAndReturnsOrderId() {
        luaReturns(0L);
        Result result = service.seckillVoucher(7L);
        assertTrue(result.getSuccess());
        assertEquals(99L, result.getData());
        verify(rabbit).convertAndSend(SeckillMqConstants.EXCHANGE, SeckillMqConstants.ROUTING_KEY,
                new VoucherOrderMessage(99L, 42L, 7L));
    }

    @Test
    void insufficientStockDoesNotPublish() {
        luaReturns(1L);
        assertFalse(service.seckillVoucher(7L).getSuccess());
        verifyZeroInteractions(rabbit);
    }

    @Test
    void duplicateDoesNotPublish() {
        luaReturns(2L);
        assertFalse(service.seckillVoucher(7L).getSuccess());
        verifyZeroInteractions(rabbit);
    }

    @Test
    void sendFailurePropagatesWithoutRetry() {
        luaReturns(0L);
        doThrow(new AmqpConnectException(new ConnectException("test connection refused")))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(VoucherOrderMessage.class));
        assertThrows(AmqpConnectException.class, () -> service.seckillVoucher(7L));
        verify(rabbit, times(1)).convertAndSend(anyString(), anyString(), any(VoucherOrderMessage.class));
    }

    @Test
    void nullLuaResultDoesNotPublish() {
        luaReturns(null);
        assertThrows(IllegalStateException.class, () -> service.seckillVoucher(7L));
        verifyZeroInteractions(rabbit);
    }

    @Test
    void listenerDelegatesAndDoesNotSwallowFailure() {
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        VoucherOrderMessage message = new VoucherOrderMessage(99L, 42L, 7L);
        doThrow(new IllegalStateException("db failure")).when(orders).createVoucherOrder(message);
        assertThrows(IllegalStateException.class, () -> new VoucherOrderListener(orders).onMessage(message));
        verify(orders).createVoucherOrder(message);
    }

    @Test
    void jsonRoundTripAndDurableTopology() {
        SeckillRabbitConfig config = new SeckillRabbitConfig();
        VoucherOrderMessage dto = new VoucherOrderMessage(99L, 42L, 7L);
        org.springframework.amqp.core.Message message = config.rabbitMessageConverter()
                .toMessage(dto, new MessageProperties());
        assertEquals("application/json", message.getMessageProperties().getContentType());
        assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT,
                message.getMessageProperties().getDeliveryMode());
        assertEquals(dto, config.rabbitMessageConverter().fromMessage(message));
        assertTrue(config.seckillExchange().isDurable());
        assertTrue(config.seckillOrderQueue().isDurable());
        assertFalse(config.seckillOrderQueue().isAutoDelete());
        assertEquals(SeckillMqConstants.ROUTING_KEY,
                config.seckillOrderBinding(config.seckillOrderQueue(), config.seckillExchange()).getRoutingKey());
    }

    @Test
    void startupDeclarationFailurePropagates() {
        SeckillRabbitConfig config = new SeckillRabbitConfig();
        AmqpAdmin admin = mock(AmqpAdmin.class);
        doThrow(new AmqpConnectException(new ConnectException("test connection refused")))
                .when(admin).declareExchange(any());
        assertThrows(AmqpConnectException.class, () -> config.declareSeckillTopology(admin,
                config.seckillExchange(), config.seckillOrderQueue(),
                config.seckillOrderBinding(config.seckillOrderQueue(), config.seckillExchange()))
                .afterSingletonsInstantiated());
    }
}
