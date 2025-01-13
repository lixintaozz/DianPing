package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 查询商铺类型（基于Redis）
     * @return
     */
    @Override
    public Result queryTypeList() {
        String key = "cache:shop_types";
        List<String> stringList = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (stringList != null && !stringList.isEmpty())
        {
            //手动转换，将Redis中存储的List<String>反序列化为List<ShopType>
            List<ShopType> shopTypeList = new ArrayList<>();
            for (String string : stringList) {
                ShopType shopType = JSONUtil.toBean(string, ShopType.class);
                shopTypeList.add(shopType);
            }

            return Result.ok(shopTypeList);
        }

        List<ShopType> shopTypes = lambdaQuery().orderByAsc(ShopType::getSort).list();

        if (shopTypes.isEmpty())
            return Result.fail("糟糕，没有一家商铺！");

        //手动转换，将List<ShopType>序列化为List<String>用于存储到Redis
        List<String> shopStrings = new ArrayList<>();
        for (ShopType shopType : shopTypes) {
            String jsonStr = JSONUtil.toJsonStr(shopType);
            shopStrings.add(jsonStr);
        }
        stringRedisTemplate.opsForList().rightPushAll(key, shopStrings);
        return Result.ok(shopTypes);
    }
}
