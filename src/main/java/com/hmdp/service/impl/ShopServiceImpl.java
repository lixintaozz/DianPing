package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.hash.Hash;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //构建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询商铺（基于Redis实现）
     * @param id
     * @return
     */
    @Override
    public Result queryShopById(Long id) {
        //解决了缓存穿透的问题
        //Shop shop = queryWithThrough(id);

        //使用互斥锁解决缓存击穿问题
        //Shop shop = queryWithHitThrough(id);

        //使用逻辑过期解决缓存击穿问题
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null)
            return Result.fail("商铺不存在！");
        return Result.ok(shop);
    }

    /**
     * 使用逻辑过期来解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id)
    {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //查询缓存
        String s = stringRedisTemplate.opsForValue().get(key);
        //缓存未命中，直接返回null
        if (StrUtil.isBlank(s))
            return null;

        //缓存命中，检查是否过期
        RedisData redisData = JSONUtil.toBean(s, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        //如果缓存未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now()))
            return shop;

        //如果缓存过期了，尝试获取锁
        boolean tryLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
        //如果获取锁成功，先做double check，如果确实需要更新缓存，那么更新缓存
        if (tryLock)
        {
            //先做double check
            //查询缓存
            s = stringRedisTemplate.opsForValue().get(key);

            //缓存命中，检查是否过期
            redisData = JSONUtil.toBean(s, RedisData.class);
            expireTime = redisData.getExpireTime();
            Shop shop1 = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

            //如果缓存未过期，直接返回
            if (expireTime.isAfter(LocalDateTime.now()))
                return shop1;

            //开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id, 30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    Unlock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }

        //如果获取锁失败，返回旧数据
        return shop;
    }

    /**
     * 将商铺数据添加到Redis缓存
     * @param id
     * @param duration
     */
    public void saveShop2Redis(Long id, Long duration)
    {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(duration));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.如果没有输入地理坐标，那么按照原来的分页查询即可
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2. 否则，首先计算分页信息
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3. 然后根据typeId去redis中查询出来店铺的相关信息
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoLocationGeoResults = stringRedisTemplate.opsForGeo().
                radius(key, new Circle(new Point(x, y), new Distance(5, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end));
        if (geoLocationGeoResults == null)
            return Result.ok(Collections.emptyList());


        //4. 对于查询到的分页店铺信息进行截取
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> results = geoLocationGeoResults.getContent();
        if (results.size() <= from)
            //没有内容了，直接结束
            return Result.ok(Collections.emptyList());
        List<Long> ids = new ArrayList<>();
        Map<Long, Distance> distanceMap = new HashMap<>();
        results.stream().skip(from).forEach(geoLocationGeoResult -> {
            Long shopId = Long.valueOf(geoLocationGeoResult.getContent().getName());
            ids.add(shopId);
            distanceMap.put(shopId, geoLocationGeoResult.getDistance());
        });
        //5. 根据shopId去查询商铺的有关信息并设置其地理坐标信息，注意这里要按照顺序获取order by field
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = lambdaQuery().in(Shop::getId, ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId()).getValue() * 1000);
        }
        //6. 返回结果

        return Result.ok(shops);
    }

    /**
     * 使用互斥锁解决了缓存击穿问题的查询
     * @param id
     * @return
     */
    private Shop queryWithHitThrough(Long id)
    {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        Shop shop;
        if (!entries.isEmpty())
        {
            if (entries.size() == 1 && entries.containsKey(""))
                return null;
            shop = BeanUtil.fillBeanWithMap(entries, new Shop(), false);
            return shop;
        }

        String mutex = "shop:lock:" + id;
        try {
            boolean tryLock = tryLock(mutex);
            if (!tryLock) {
                Thread.sleep(50);   //这里可能会抛异常，如果finally中释放锁的话，会有问题
                return queryWithThrough(id);
            }

            //Double check，如果此时缓存已经存在，则无需再重建缓存
            //Double check的原因：获取锁后，再次检查缓存数据是否已被其他线程重建（可能是其他线程在
            // 我们获取锁的过程中重建了缓存）。如果缓存已经存在，则直接返回，不进行重建；如果缓存不存在，则开始重建缓存。
            entries = stringRedisTemplate.opsForHash().entries(key);
            if (!entries.isEmpty())
            {
                if (entries.size() == 1 && entries.containsKey(""))
                    return null;
                shop = BeanUtil.fillBeanWithMap(entries, new Shop(), false);
                return shop;
            }

            shop = getById(id);

            if (shop == null) {
                Map<String, String> objectHash = new HashMap<>();
                objectHash.put("", "");
                stringRedisTemplate.opsForHash().putAll(key, objectHash);
                //为空值设置过期时间
                stringRedisTemplate.expire(key, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            Map<String, Object> stringObjectMap = BeanUtil.beanToMap(shop, new HashMap<>(), CopyOptions.create()
                    .ignoreNullValue().setFieldValueEditor((field, value) ->{
                        if (value == null)
                            return null;
                        return value.toString();
                    }));
            stringRedisTemplate.opsForHash().putAll(key, stringObjectMap);
            stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Unlock(mutex);    //这里没有加入finally中是因为递归的实现存在逻辑问题，正常情况下是需要加入finally块中的
        return shop;
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
        Boolean delete = stringRedisTemplate.delete(key);
    }



    /**
     * 解决了缓存穿透问题的查询
     * @param id
     * @return
     */
    private Shop queryWithThrough(Long id)
    {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        Shop shop;
        if (!entries.isEmpty())
        {
            if (entries.size() == 1 && entries.containsKey(""))
                return null;
            shop = BeanUtil.fillBeanWithMap(entries, new Shop(), false);
            return shop;
        }


        shop = getById(id);
        if (shop == null) {
            Map<String, String> objectHash = new HashMap<>();
            objectHash.put("", "");
            stringRedisTemplate.opsForHash().putAll(key, objectHash);
            //为空值设置过期时间
            stringRedisTemplate.expire(key, RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(shop, new HashMap<>(), CopyOptions.create()
                .ignoreNullValue().setFieldValueEditor((field, value) ->{
                    if (value == null)
                        return null;
                    return value.toString();
                }));
        stringRedisTemplate.opsForHash().putAll(key, stringObjectMap);
        stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop.getId() == null)
            return Result.fail("商铺id不存在!");

        //1. 先更新数据库
        updateById(shop);
        //2. 再删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
