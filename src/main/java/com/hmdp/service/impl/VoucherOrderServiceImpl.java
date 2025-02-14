package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.concurrent.*;

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

    private IVoucherOrderService currentProxy;

    //创建阻塞队列
    private BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService Seckill_Voucher_Order_Executor = Executors.newSingleThreadExecutor();

    //这里@PostConstruct的作用是在当前类初始化完成以后执行
    @PostConstruct
    private void init(){
        Seckill_Voucher_Order_Executor.submit(new Task());
    }

    private class Task implements Runnable
    {

        @Override
        public void run() {
            while (true){
                //1. 首先尝试从阻塞队列取出订单
                VoucherOrder voucherOrder = null;
                try {
                    voucherOrder = blockingQueue.take();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                //2. 然后创建分布式锁
                RLock redissonClientLock = redissonClient.getLock("VoucherOrder:Submit");
                //3. 尝试获取锁
                boolean tryLock = false;
                try {
                    tryLock = redissonClientLock.tryLock(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                //4. 如果获取锁成功
                if (tryLock) {
                    //5. 就将订单数据提交到数据库
                    try {
                        currentProxy.CreateOrder(voucherOrder);
                    } finally {
                        //6. 释放锁
                        redissonClientLock.unlock();
                    }
                }else {
                    //7. 否则将订单数据重新加入阻塞队列
                    blockingQueue.add(voucherOrder);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    private static final DefaultRedisScript<Long> RedisScript = new DefaultRedisScript<>();
    static {
        RedisScript.setLocation(new ClassPathResource("seckill.lua"));
        RedisScript.setResultType(Long.class);
    }



    /**
     * 秒杀券下单（优化版）
     * @param voucherId
     * @return
     */
    @Override
    public Result
    seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        //1. 调用Lua脚本判断能否下单
        Long resultVal = stringRedisTemplate.execute(
                RedisScript,
                Collections.emptyList(),
                userId.toString(),
                voucherId.toString()
        );

        //2. 如果无法下单，那么直接返回错误信息
        if (resultVal != 0)
            return Result.fail(resultVal == 1 ? "库存不足！" : "不能重复下单！");

        //3. 如果下单成功，那么生成订单信息，并将其加入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        Long voucherOrderId = redisIDWorker.nextId("voucherOrder");
        voucherOrder.setId(voucherOrderId);

        //4. 先获取当前类的代理对象，然后将订单信息加入阻塞队列
        currentProxy = (IVoucherOrderService) AopContext.currentProxy();
        blockingQueue.add(voucherOrder);

        //5. 返回订单id
        return Result.ok(voucherOrderId);
    }





    /**
     * 秒杀券下单（未优化版）
     *
     * @param
     */
/*    @Override
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
    }*/

    @Transactional
    public void CreateOrder(VoucherOrder voucherOrder) {
        iSeckillVoucherService.lambdaUpdate()
                              .setSql("stock = stock - 1")
                              .eq(SeckillVoucher::getVoucherId, voucherOrder.getVoucherId())
                              .gt(SeckillVoucher::getStock, 0)
                              .update();

        save(voucherOrder);
    }
}
