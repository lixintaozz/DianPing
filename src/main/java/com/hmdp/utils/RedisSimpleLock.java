package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class RedisSimpleLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    private String name;

    private String id_prefix = UUID.randomUUID().toString(true) + "-";

    private static final String lock_prefix = "lock:";

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
        String s = stringRedisTemplate.opsForValue().get(lock_prefix + name);
        long id = Thread.currentThread().getId();
        String ThreadId = id_prefix + id;
        //如果锁识别成功，那么释放锁
        if (ThreadId.equals(s))
            stringRedisTemplate.delete(lock_prefix + name);
    }
}
