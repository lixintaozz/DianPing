package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.hash.Hash;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
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
        Shop shop = queryWithHitThrough(id);
        if (shop == null)
            return Result.fail("商铺不存在！");
        return Result.ok(shop);
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
                Thread.sleep(50);
                return queryWithThrough(id);
            }

            //Double check，如果此时缓存已经存在，则无需再重建缓存
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

        Unlock(mutex);
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
