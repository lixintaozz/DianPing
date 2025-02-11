package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.RedisSimpleLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIDWorker redisIDWorker;

    @Autowired
    private RedissonClient redissonClient;
    /**
     * 秒杀券下单
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1. 查询优惠券信息
        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
        //2. 检查秒杀活动是否开始
        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime()))
            return Result.fail("秒杀活动尚未开始！");
        //3. 检查秒杀活动是否结束
        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime()))
            return Result.fail("秒杀活动已经结束！");
        //4. 检查库存是否充足
        if (seckillVoucher.getStock() < 1)
            return Result.fail("库存不足！");

        //5. 检查用户是否已经购买过该秒杀券
        Long userId = UserHolder.getUser().getId();
        //创建锁对象
        //RedisSimpleLock redisSimpleLock = new RedisSimpleLock(stringRedisTemplate, "order:" +userId);
        RLock rLock = redissonClient.getLock("order:" + userId);

        //尝试获取锁
        boolean tryLock = rLock.tryLock();
        //如果获取锁失败，直接返回错误信息
        if (!tryLock)
            return Result.fail("禁止用户重复购票！");

        //先获取代理对象，然后执行函数
        //知识点：Spring的代理对象只在类外部生效，而在类的内部使用的还是原本的类并非代理对象，所以这里要手动获取代理对象
        try {
            IVoucherOrderService currentProxy = (IVoucherOrderService) AopContext.currentProxy();
            return currentProxy.CreateOrder(voucherId, userId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            rLock.unlock();
        }
    }

    @Transactional
    public Result CreateOrder(Long voucherId, Long userId) {
        int count = lambdaQuery().eq(VoucherOrder::getVoucherId, voucherId).eq(VoucherOrder::getUserId, userId).count();
        if (count > 0)
            return Result.fail("用户已经购买过一次该优惠券了！");

        //6. 更新数据库表中优惠券的数量
        iSeckillVoucherService.lambdaUpdate()
                              .setSql("stock = stock - 1")
                              .eq(SeckillVoucher::getVoucherId, voucherId)
                              .gt(SeckillVoucher::getStock, 0)
                              .update();
        //7. 创建订单并保存
        VoucherOrder voucherOrder = new VoucherOrder();
        //设置订单id
        Long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        //设置用户id
        voucherOrder.setUserId(userId);
        //设置购买的代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //8. 返回订单id
        return Result.ok(orderId);
    }
}
