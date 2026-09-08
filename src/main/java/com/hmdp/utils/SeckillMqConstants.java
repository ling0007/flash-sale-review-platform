package com.hmdp.utils;

public final class SeckillMqConstants {
    public static final String EXCHANGE = "hmdp.seckill.direct";
    public static final String QUEUE = "hmdp.seckill.order.queue";
    public static final String ROUTING_KEY = "seckill.order.create";

    private SeckillMqConstants() { }
}
