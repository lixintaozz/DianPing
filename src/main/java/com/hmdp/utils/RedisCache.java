package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class RedisCache {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //构建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 将任意对象序列化Json并存储在String类型的key中
     * @param key
     * @param o
     * @param expireTime
     * @param timeUnit
     */
    public void set(String key, Object o, Long expireTime, TimeUnit timeUnit)
    {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(o), expireTime, timeUnit);
    }

    /**
     * 将任意对象序列化Json并存储在String类型的key中，可以设置逻辑过期时间
     * @param key
     * @param o
     * @param expireTime
     * @param timeUnit
     */
    public void setWithLogicalExpire(String key, Object o, Long expireTime, TimeUnit timeUnit)
    {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expireTime)));
        redisData.setData(o);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 解决了缓存穿透问题的查询
     * @param key
     * @param type
     * @param id
     * @param ExpireTime
     * @param timeUnit
     * @param function
     * @return
     * @param <R>
     * @param <ID>
     */
    private <R, ID> R queryWithThrough(
            String key, Class<R> type, ID id, Long ExpireTime, TimeUnit timeUnit, Function<ID, R> function)
    {
        String s = stringRedisTemplate.opsForValue().get(key);
        if (!StrUtil.isBlank(s))
            return JSONUtil.toBean(s, type);

        //redis查询到的为空串
        if (s != null)
            return null;

        R r = function.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", ExpireTime, timeUnit);
            return null;
        }

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), ExpireTime, timeUnit);
        return r;
    }

    /**
     * 使用逻辑过期来解决缓存击穿
     * @param key
     * @param type
     * @param id
     * @param ExpireTime
     * @param timeUnit
     * @param function
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithLogicalExpire(
            String key, Class<R> type, ID id, Long ExpireTime, TimeUnit timeUnit, Function<ID, R> function)
    {
        //查询缓存
        String s = stringRedisTemplate.opsForValue().get(key);
        //缓存未命中，直接返回null
        if (StrUtil.isBlank(s))
            return null;

        //缓存命中，检查是否过期
        com.hmdp.entity.RedisData redisData = JSONUtil.toBean(s, com.hmdp.entity.RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        //如果缓存未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now()))
            return r;

        //如果缓存过期了，尝试获取锁
        boolean tryLock = tryLock("lock:" + id);
        //如果获取锁成功，先做double check，如果确实需要更新缓存，那么更新缓存
        if (tryLock)
        {
            //先做double check
            //查询缓存
            s = stringRedisTemplate.opsForValue().get(key);

            //缓存命中，检查是否过期
            redisData = JSONUtil.toBean(s, com.hmdp.entity.RedisData.class);
            expireTime = redisData.getExpireTime();
            R r1 = JSONUtil.toBean((JSONObject) redisData.getData(), type);

            //如果缓存未过期，直接返回
            if (expireTime.isAfter(LocalDateTime.now()))
                return r1;

            //开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //1. 先查询数据库
                    R r2 = function.apply(id);
                    //2. 重建缓存
                    setWithLogicalExpire(key, r2, ExpireTime, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    Unlock("lock:" + id);
                }
            });
        }

        //如果获取锁失败，返回旧数据
        return r;
    }



    /**
     * 尝试加锁
     * @param key
     * @return
     */
    private boolean tryLock(String key)
    {
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 20, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    /**
     * 删除锁
     * @param key
     */
    private void Unlock(String key)
    {
        stringRedisTemplate.delete(key);
    }
}
