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
