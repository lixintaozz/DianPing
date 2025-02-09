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
import org.springframework.beans.factory.annotation.Autowired;
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
    private RedisIDWorker redisIDWorker;
    /**
     * 秒杀券下单
     * @param voucherId
     * @return
     */
    @Override
    @Transactional
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
        //5. 更新数据库表中优惠券的数量
        iSeckillVoucherService.lambdaUpdate()
                              .setSql("stock = stock - 1")
                              .eq(SeckillVoucher::getVoucherId, voucherId)
                              .update();
        //6. 创建订单并保存
        VoucherOrder voucherOrder = new VoucherOrder();
        //设置订单id
        Long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        //设置用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //设置购买的代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //7. 返回订单id
        return Result.ok(orderId);
    }
}
