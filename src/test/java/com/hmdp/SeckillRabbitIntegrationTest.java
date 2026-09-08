package com.hmdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderPersistenceService;
import com.hmdp.utils.SeckillMqConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Explicit opt-in: uses real Redis, RabbitMQ and MySQL. Use a dedicated RabbitMQ virtual host.
@EnabledIfEnvironmentVariable(named = "RUN_SECKILL_INTEGRATION", matches = "true")
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@AutoConfigureMockMvc
class SeckillRabbitIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired VoucherOrderPersistenceService persistence;

    @Test
    void realRequestLuaMqDatabaseAndRollback() throws Exception {
        long voucher = 8000000000000L + System.currentTimeMillis();
        long emptyVoucher = voucher + 1;
        long user = voucher + 2;
        String token = "seckill-verification-" + UUID.randomUUID();
        String loginKey = "login:token:" + token;
        assertEquals(0, admin.getQueueProperties(SeckillMqConstants.QUEUE).get("QUEUE_MESSAGE_COUNT"),
                "Run against a dedicated empty virtual host");
        try {
            jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES (?,2,NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY)), (?,0,NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY))",
                    voucher, emptyVoucher);
            redis.opsForValue().set("seckill:stock:" + voucher, "2");
            redis.opsForValue().set("seckill:stock:" + emptyVoucher, "0");
            redis.opsForHash().putAll(loginKey, Collections.singletonMap("id", Long.toString(user)));
            redis.expire(loginKey, Duration.ofMinutes(10));

            JsonNode accepted = request(voucher, token);
            assertTrue(accepted.get("success").asBoolean());
            long orderId = accepted.get("data").asLong();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertEquals(1, admin.getQueueProperties(SeckillMqConstants.QUEUE).get("QUEUE_MESSAGE_COUNT")));
            assertEquals("1", redis.opsForValue().get("seckill:stock:" + voucher));
            assertTrue(redis.opsForSet().isMember("seckill:order:" + voucher, Long.toString(user)));
            assertFalse(request(voucher, token).get("success").asBoolean());
            assertFalse(request(emptyVoucher, token).get("success").asBoolean());
            assertEquals(1, admin.getQueueProperties(SeckillMqConstants.QUEUE).get("QUEUE_MESSAGE_COUNT"));

            listeners.getListenerContainers().forEach(container -> container.start());
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE id=? AND user_id=? AND voucher_id=?",
                            Integer.class, orderId, user, voucher)));
            assertEquals(1, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucher));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals(0, admin.getQueueProperties(SeckillMqConstants.QUEUE).get("QUEUE_MESSAGE_COUNT")));

            assertTrue(AopUtils.isAopProxy(persistence));
            // A different user passes the duplicate check but collides with the existing order PK.
            // MySQL must roll back the stock UPDATE executed before this failing INSERT.
            assertThrows(RuntimeException.class, () -> persistence.createVoucherOrder(new VoucherOrder()
                    .setId(orderId).setUserId(user + 1).setVoucherId(voucher)));
            assertEquals(1, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucher));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Integer.class, voucher));

            boolean oldConsumer = Thread.getAllStackTraces().values().stream().flatMap(Arrays::stream)
                    .anyMatch(frame -> frame.getClassName().contains("VoucherOrderHandler"));
            assertFalse(oldConsumer, "Redis Stream seckill consumer must not exist");
            mvc.perform(get("/shop/1")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            mvc.perform(get("/shop-type/list")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
            mvc.perform(get("/blog/hot").param("current", "1")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        } finally {
            listeners.getListenerContainers().forEach(container -> container.stop());
            // Only test-specific rows and Redis keys are removed. No flush or queue purge.
            jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id IN (?,?)", voucher, emptyVoucher);
            jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id IN (?,?)", voucher, emptyVoucher);
            redis.delete(Arrays.asList(loginKey, "seckill:stock:" + voucher, "seckill:stock:" + emptyVoucher,
                    "seckill:order:" + voucher, "seckill:order:" + emptyVoucher));
        }
    }

    private JsonNode request(long voucherId, String token) throws Exception {
        return json.readTree(mvc.perform(post("/voucher-order/seckill/" + voucherId)
                .header("authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
