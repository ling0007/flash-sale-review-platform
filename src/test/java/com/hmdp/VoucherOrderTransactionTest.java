package com.hmdp;

import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.VoucherOrderPersistenceService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoucherOrderTransactionTest {
    private final VoucherOrderMapper mapper = mock(VoucherOrderMapper.class);
    private final ISeckillVoucherService stock = mock(ISeckillVoucherService.class);
    private final RecordingTransactionManager transactions = new RecordingTransactionManager();
    private final VoucherOrderServiceImpl orders = new VoucherOrderServiceImpl();
    private final RLock lock = mock(RLock.class);
    private final VoucherOrderMessage message = new VoucherOrderMessage(99L, 42L, 7L);

    @BeforeEach
    void setUp() {
        VoucherOrderPersistenceService target = new VoucherOrderPersistenceService(mapper, stock);
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        ReflectionTestUtils.setField(orders, "persistenceService", factory.getProxy());
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock("lock:order:42")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        ReflectionTestUtils.setField(orders, "redissonClient", redisson);
        when(mapper.selectCount(any())).thenReturn(0);
        SeckillVoucherMapper stockMapper = mock(SeckillVoucherMapper.class);
        when(stockMapper.update(isNull(), any())).thenReturn(1);
        when(stock.update()).thenReturn(new UpdateChainWrapper<>(stockMapper));
        when(mapper.insert(any())).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        doAnswer(invocation -> {
            assertTrue(transactions.committed || transactions.rolledBack, "Unlock must follow transaction completion");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(lock).unlock();
    }

    @Test
    void transactionCommitsBeforeLockRelease() {
        orders.createVoucherOrder(message);
        assertTrue(transactions.committed);
        verify(mapper).insert(argThat(order -> order.getId() == 99L && order.getUserId() == 42L
                && order.getVoucherId() == 7L));
        verify(lock).unlock();
    }

    @Test
    void insertFailureRollsBackBeforeLockRelease() {
        doThrow(new IllegalStateException("insert failed")).when(mapper).insert(any(VoucherOrder.class));
        assertThrows(IllegalStateException.class, () -> orders.createVoucherOrder(message));
        assertTrue(transactions.rolledBack);
        assertFalse(transactions.committed);
        verify(lock).unlock();
    }

    @Test
    void zeroInsertedRowsAlsoRollBack() {
        doReturn(0).when(mapper).insert(any(VoucherOrder.class));
        assertThrows(IllegalStateException.class, () -> orders.createVoucherOrder(message));
        assertTrue(transactions.rolledBack);
    }

    @Test
    void duplicateOrderDoesNotInsertOrDeductAgain() {
        when(mapper.selectCount(any())).thenReturn(1);
        clearInvocations(stock);
        orders.createVoucherOrder(message);
        verify(mapper, never()).insert(any(VoucherOrder.class));
        verifyZeroInteractions(stock);
    }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        boolean committed;
        boolean rolledBack;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
