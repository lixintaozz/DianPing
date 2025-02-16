package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class RedisSimpleLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    private String name;

    private static final String id_prefix = UUID.randomUUID().toString(true) + "-";

    private static final String lock_prefix = "lock:";

    private static final DefaultRedisScript<Long> redisScript;

    static
    {
        redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(new ClassPathResource("unlock.lua"));
        redisScript.setResultType(Long.class);
    }

    public RedisSimpleLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String key = lock_prefix + name;
        long id = Thread.currentThread().getId();
        //使用UUID加线程ID来标识锁
        //id_prefix能够唯一确定集群中的一台机器，id能够唯一标识机器中的一个线程，
        //它们组合起来就能唯一确定一个集群中的一个线程


        //在同一台机器上运行多个线程时，它们 默认是共享同一个 Spring IoC 容器 的。
        //这是因为 Spring 容器是基于 JVM（Java Virtual Machine）实例的，而同一个
        // JVM 运行的所有线程都会共享一个 Spring 应用上下文（IoC 容器）。
        String val = id_prefix + id;
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, val, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    @Override
    public void unlock() {
        /*
        使用Lua脚本来与redis交互的最大好处就是能够保证操作的原子性，Java语句可能会被阻塞，但原子操作不会
         */

        //这里使用Lua脚本来保证"锁识别成功"和"锁释放"这两个操作的原子性
        stringRedisTemplate.execute(
                redisScript,
                Collections.singletonList(lock_prefix + name),
                id_prefix + Thread.currentThread().getId()
                );
    }
}
