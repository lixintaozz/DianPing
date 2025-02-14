package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIDWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/*@SpringBootTest
class HmDianPingApplicationTests {
    *//*
    知识点：因为ShopServiceImpl中使用了@Transactional注解，所以生成的Bean对象是一个动态代理对象
           同时，ShopServiceImpl又实现了IShopService接口，所以Spring默认使用JDK动态代理来生成代理对象
           这个代理对象的类型为IShopService，所以这里只能注入IShopService

           如果ShopServiceImpl没有实现IShopService接口，那么就会使用CGLIB动态代理来生成代理对象，
           这个代理对象的类型为ShopServiceImpl类型的子类，那么就可以直接注入ShopServiceImpl
     *//*


    @Autowired
    private IShopService shopService;
    @Autowired
    private RedisIDWorker redisIDWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void save(){
        shopService.saveShop2Redis(5L, 20L);
    }

    @Test
    void generate()
    {
        for (int i = 0; i < 100; ++ i)
            System.out.println(redisIDWorker.nextId("shop"));
    }

    @Test
    void saveShopGeo2Redis(){
        //1. 首先查询全部商铺的信息
        List<Shop> shops = shopService.list();
        //2. 根据商铺id，将商铺划分到所在的类型里面去
        Map<Long, List<Shop>> shopMap = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3. 将商铺数据转换为redis所需要的数据格式
        for (Map.Entry<Long, List<Shop>> entry: shopMap.entrySet())
        {
            String key = RedisConstants.SHOP_GEO_KEY + entry.getKey();
            List<RedisGeoCommands.GeoLocation<String>> geoLocations = entry.getValue().stream().map(shop ->
                    new RedisGeoCommands.GeoLocation<String>(shop.getId().toString(), new Point(shop.getX(), shop.getY()))
            ).collect(Collectors.toList());
            stringRedisTemplate.opsForGeo().add(key, geoLocations);
        }
    }
}*/
