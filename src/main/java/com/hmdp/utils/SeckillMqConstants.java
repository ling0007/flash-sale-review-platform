package com.hmdp.utils;

public final class SeckillMqConstants {
    public static final String EXCHANGE = "hmdp.seckill.direct";
    public static final String QUEUE = "hmdp.seckill.order.queue";
    public static final String ROUTING_KEY = "seckill.order.create";
    public static final String ERROR_EXCHANGE = "hmdp.error.direct";
    public static final String ERROR_QUEUE = "hmdp.seckill.error.queue";
    public static final String ERROR_ROUTING_KEY = "seckill.order";

    private SeckillMqConstants() { }
}
