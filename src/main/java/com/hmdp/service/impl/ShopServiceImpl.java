package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.hash.Hash;
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
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        Shop shop;
        if (!entries.isEmpty())
        {
            if (entries.size() == 1 && entries.containsKey(""))
                return Result.fail("商铺信息不存在！");
            shop = BeanUtil.fillBeanWithMap(entries, new Shop(), false);
            return Result.ok(shop);
        }


        shop = getById(id);
        if (shop == null) {
            Map<String, String> objectHash = new HashMap<>();
            objectHash.put("", "");
            stringRedisTemplate.opsForHash().putAll(key, objectHash);
            return Result.fail("商铺不存在！");
        }

        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(shop, new HashMap<>(), CopyOptions.create()
                .ignoreNullValue().setFieldValueEditor((field, value) ->{
                    if (value == null)
                        return null;
                    return value.toString();
                }));
        stringRedisTemplate.opsForHash().putAll(key, stringObjectMap);
        stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
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
