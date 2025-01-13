package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
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

import java.util.HashMap;
import java.util.Map;

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
            shop = BeanUtil.fillBeanWithMap(entries, new Shop(), false);
            return Result.ok(shop);
        }

        shop = getById(id);
        if (shop == null)
            return Result.fail("商铺不存在！");

        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(shop, new HashMap<>(), CopyOptions.create()
                .ignoreNullValue().setFieldValueEditor((field, value) ->{
                    if (value == null)
                        return null;
                    return value.toString();
                }));
        stringRedisTemplate.opsForHash().putAll(key, stringObjectMap);
        return Result.ok(shop);
    }
}
