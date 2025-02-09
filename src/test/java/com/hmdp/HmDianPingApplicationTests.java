package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;


@SpringBootTest
class HmDianPingApplicationTests {
    /*
    知识点：因为ShopServiceImpl中使用了@Transactional注解，所以生成的Bean对象是一个动态代理对象
           同时，ShopServiceImpl又实现了IShopService接口，所以Spring默认使用JDK动态代理来生成代理对象
           这个代理对象的类型为IShopService，所以这里只能注入IShopService

           如果ShopServiceImpl没有实现IShopService接口，那么就会使用CGLIB动态代理来生成代理对象，
           这个代理对象的类型为ShopServiceImpl类型的子类，那么就可以直接注入ShopServiceImpl
     */


    @Autowired
    private IShopService shopService;

    @Test
    void save(){
        shopService.saveShop2Redis(5L, 20L);
    }

}
