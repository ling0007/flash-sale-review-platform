package com.hmdp;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.impl.VoucherOrderPersistenceService;
import com.hmdp.utils.SeckillMqConstants;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

// 真实中间件验收。仅测试使用故障注入，不在生产 Listener 添加开关或异常分支。
@EnabledIfEnvironmentVariable(named = "RUN_SECKILL_INTEGRATION", matches = "true")
@Import(SeckillConsumerReliabilityTest.FaultConfiguration.class)
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.simple.concurrency=2",
        "spring.rabbitmq.listener.simple.max-concurrency=2",
        "spring.rabbitmq.listener.simple.prefetch=1"
})
class SeckillConsumerReliabilityTest {
    private static final AtomicLong IDS = new AtomicLong(9000000000000L + System.currentTimeMillis());
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired CachingConnectionFactory connectionFactory;
    @Autowired VoucherOrderPersistenceService persistence;
    @Autowired IVoucherOrderService orders;
    @Autowired Faults faults;
    private long voucher;
    private long user;
    private long orderId;

    @BeforeEach
    void seed() {
        faults.reset();
        voucher = IDS.addAndGet(10);
        user = voucher + 1;
        orderId = voucher + 2;
        assertEquals(0, ready(SeckillMqConstants.QUEUE), "请使用无其他消费者的专用 vhost");
        assertEquals(0, ready(SeckillMqConstants.ERROR_QUEUE));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='uk_voucher_order_user_voucher' AND non_unique=0", Integer.class));
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES (?,5,NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY))", voucher);
    }

    @AfterEach
    void cleanup() {
        stopConsumers();
        // 仅清理本用例创建的数据；错误消息在对应测试断言后读取，不清空或删除队列。
        jdbc.update("DELETE FROM tb_voucher_order WHERE voucher_id=?", voucher);
        jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", voucher);
    }

    @Test
    void normalMessageAndSequentialRedeliveryOnlyDeductOnce() {
        startConsumers();
        send(orderId);
        awaitOrder();
        stopConsumers();
        assertEquals(0, ready(SeckillMqConstants.QUEUE));
        send(orderId);
        awaitReady(SeckillMqConstants.QUEUE, 1);
        startConsumers();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertTrue(faults.reads.get() >= 2));
        stopConsumers();
        assertEquals(1, countOrders());
        assertEquals(4, stock());
        assertEquals(0, ready(SeckillMqConstants.QUEUE));
        assertEquals(0, ready(SeckillMqConstants.ERROR_QUEUE));
    }

    @Test
    void concurrentDuplicateMessagesDoNotDoubleDeduct() {
        // 两条同业务键、不同 orderId 的消息预先入队，由两个真实消费者并发处理。
        send(orderId);
        send(orderId + 1);
        awaitReady(SeckillMqConstants.QUEUE, 2);
        startConsumers();
        awaitOrder();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertTrue(faults.reads.get() >= 2));
        stopConsumers();
        assertEquals(1, countOrders());
        assertEquals(4, stock());
        assertEquals(0, ready(SeckillMqConstants.QUEUE));
        assertEquals(0, ready(SeckillMqConstants.ERROR_QUEUE));
    }

    @Test
    void temporaryInsertFailuresRetryInFreshTransactions() {
        faults.insertFailures = 2;
        startConsumers();
        send(orderId);
        awaitOrder();
        stopConsumers();
        assertEquals(3, faults.attempts.get());
        Set<Object> transactionResources = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        transactionResources.addAll(faults.resources);
        assertEquals(3, transactionResources.size());
        assertTrue(faults.times.get(1) - faults.times.get(0) >= TimeUnit.MILLISECONDS.toNanos(900));
        assertTrue(faults.times.get(2) - faults.times.get(1) >= TimeUnit.MILLISECONDS.toNanos(1900));
        assertEquals(4, stock(), "前两次 INSERT 失败必须回滚各自的库存扣减");
        assertEquals(0, ready(SeckillMqConstants.ERROR_QUEUE));
    }

    @Test
    void exhaustedRetriesRepublishPersistentMessageWithFailureContext() {
        faults.insertFailures = Integer.MAX_VALUE;
        startConsumers();
        send(orderId);
        awaitReady(SeckillMqConstants.ERROR_QUEUE, 1);
        stopConsumers();
        assertEquals(3, faults.attempts.get());
        assertEquals(0, countOrders());
        assertEquals(5, stock());
        assertEquals(0, ready(SeckillMqConstants.QUEUE));
        Message failed = rabbit.receive(SeckillMqConstants.ERROR_QUEUE, 5000);
        assertNotNull(failed);
        assertEquals(MessageDeliveryMode.PERSISTENT, failed.getMessageProperties().getReceivedDeliveryMode());
        Map<String, Object> headers = failed.getMessageProperties().getHeaders();
        assertEquals(SeckillMqConstants.EXCHANGE, headers.get(RepublishMessageRecoverer.X_ORIGINAL_EXCHANGE));
        assertEquals(SeckillMqConstants.ROUTING_KEY, headers.get(RepublishMessageRecoverer.X_ORIGINAL_ROUTING_KEY));
        assertTrue(headers.get(RepublishMessageRecoverer.X_EXCEPTION_STACKTRACE).toString().contains("injected insert failure"));
        assertNotNull(headers.get(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE));
        assertEquals(new VoucherOrderMessage(orderId, user, voucher), rabbit.getMessageConverter().fromMessage(failed));
    }

    @Test
    void queuedPersistentMessageSurvivesConsumerConnectionRestart() {
        startConsumers();
        stopConsumers();
        send(orderId);
        awaitReady(SeckillMqConstants.QUEUE, 1);
        // 关闭应用侧 RabbitMQ 连接后重新连接，不删除 broker 队列。
        connectionFactory.resetConnection();
        assertEquals(1, ready(SeckillMqConstants.QUEUE));
        MessageProperties properties = rabbit.execute(channel -> {
            assertTrue(channel.queueDeclarePassive(SeckillMqConstants.QUEUE).getMessageCount() == 1);
            com.rabbitmq.client.GetResponse response = channel.basicGet(SeckillMqConstants.QUEUE, false);
            assertEquals(2, response.getProps().getDeliveryMode());
            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
            return new MessageProperties();
        });
        assertNotNull(properties);
        startConsumers();
        awaitOrder();
        stopConsumers();
        assertEquals(4, stock());
    }

    @Test
    void databaseUniqueRaceRollsBackLoserEvenWithoutRedisLock() throws Exception {
        faults.bothRead = new CountDownLatch(2);
        // 模拟 Redis 锁无法提供互斥，强制两条真实 Service 调用同时查重未命中。
        // 只在该测试替换锁依赖，仍执行真实 Spring 事务、MySQL UPDATE/INSERT 和事务外幂等判断。
        Object originalRedisson = ReflectionTestUtils.getField(orders, "redissonClient");
        RedissonClient noMutualExclusion = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(lock.tryLock()).thenReturn(true);
        when(noMutualExclusion.getLock(anyString())).thenReturn(lock);
        ReflectionTestUtils.setField(orders, "redissonClient", noMutualExclusion);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> orders.createVoucherOrder(new VoucherOrderMessage(orderId, user, voucher)));
            Future<?> b = pool.submit(() -> orders.createVoucherOrder(new VoucherOrderMessage(orderId + 1, user, voucher)));
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
            assertEquals(1, faults.uniqueConflicts.get(), "必须确实触发唯一键冲突，不能只测串行查重");
            assertEquals(1, countOrders());
            assertEquals(4, stock(), "失败事务必须回滚扣库存，即使它已执行 UPDATE");
            // 重新投递经正常业务入口识别为已处理，不再扣库存或抛异常。
            orders.createVoucherOrder(new VoucherOrderMessage(orderId, user, voucher));
            assertEquals(4, stock());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            ReflectionTestUtils.setField(orders, "redissonClient", originalRedisson);
        }
    }

    private void send(long id) { rabbit.convertAndSend(SeckillMqConstants.EXCHANGE, SeckillMqConstants.ROUTING_KEY, new VoucherOrderMessage(id, user, voucher)); }
    private int ready(String queue) { return (Integer) Objects.requireNonNull(admin.getQueueProperties(queue)).get("QUEUE_MESSAGE_COUNT"); }
    private int stock() { return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucher); }
    private int countOrders() { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=?", Integer.class, voucher); }
    private void startConsumers() { listeners.getListenerContainers().forEach(container -> container.start()); }
    private void stopConsumers() { listeners.getListenerContainers().forEach(container -> container.stop()); }
    private void awaitOrder() { await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(1, countOrders())); }
    private void awaitReady(String queue, int count) { await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertEquals(count, ready(queue))); }

    @TestConfiguration
    static class FaultConfiguration {
        @Bean
        static Faults reliabilityFaults() { return new Faults(); }

        @Bean
        static BeanPostProcessor mapperFaultInjector(Faults faults) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof VoucherOrderMapper)) { return bean; }
                    // 在真实 Mapper 外加测试代理，避开旧 Mockito 无法 spy JDK final 代理的问题。
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.addAdvice(faults);
                    return proxy.getProxy();
                }
            };
        }
    }

    static class Faults implements MethodInterceptor {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger uniqueConflicts = new AtomicInteger();
        final List<Long> times = new CopyOnWriteArrayList<>();
        final List<Object> resources = new CopyOnWriteArrayList<>();
        volatile int insertFailures;
        volatile CountDownLatch bothRead;

        void reset() {
            attempts.set(0);
            reads.set(0);
            uniqueConflicts.set(0);
            times.clear();
            resources.clear();
            insertFailures = 0;
            bothRead = null;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            if (invocation.getMethod().getName().equals("insert")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                times.add(System.nanoTime());
                resources.add(TransactionSynchronizationManager.getResourceMap().values().iterator().next());
                if (attempts.incrementAndGet() <= insertFailures) {
                    throw new TransientDataAccessResourceException("injected insert failure");
                }
            }
            Object result;
            try {
                result = invocation.proceed();
            } catch (DuplicateKeyException duplicate) {
                uniqueConflicts.incrementAndGet();
                throw duplicate;
            }
            if (invocation.getMethod().getName().equals("selectCount")) {
                int read = reads.incrementAndGet();
                CountDownLatch barrier = bothRead;
                if (barrier != null && read <= 2) {
                    barrier.countDown();
                    assertTrue(barrier.await(5, TimeUnit.SECONDS));
                }
            }
            return result;
        }
    }
}
