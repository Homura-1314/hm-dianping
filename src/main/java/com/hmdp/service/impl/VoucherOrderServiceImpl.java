package com.hmdp.service.impl;

import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import static com.hmdp.utils.RedisConstants.LOCK_ORDER_KEY;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;

import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private RedisIdWorker  redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final BlockingQueue<VoucherOrder> voucherOrderQueue = new LinkedBlockingQueue<>(1024 * 1024);
    private static final ExecutorService EXECUTORSERVICE = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        EXECUTORSERVICE.execute(new VoucherOrderTask());
    }
    private class VoucherOrderTask implements Runnable {

        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = voucherOrderQueue.take();
                    // 创建订单
                    handlevoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常：{}",e.getMessage());
                }

            }
        }

        private void handlevoucherOrder(VoucherOrder voucherOrder) {
            RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + voucherOrder.getUserId());
            boolean isLock = lock.tryLock();
            if (!isLock){
                Result.fail("只能下单一个");
                return;
            }
            try {
                synchronized(voucherOrder.getUserId().toString().intern()) {
                    proxy.createVoucherOrder(voucherOrder);
                }
            } finally {
                lock.unlock();
            }
        }
    }
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString()
        );
        int value = result.intValue();
        if (value != 0){
            return Result.fail(value == 1 ? "库存不足" : "不能重复下单");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        Long id = redisIdWorker.nextId("order");
        voucherOrder.setId(id);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        voucherOrderQueue.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(id);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
/// /        SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        RLock lock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock){
//            return Result.fail("只能下单一个");
//        }
//        try {
//            synchronized(userId.toString().intern()) {
//                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//                return proxy.createVoucherOrder(voucherId);
//            }
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void createVoucherOrder(VoucherOrder VoucherOrder) {
            Long userId = VoucherOrder.getUserId();
            int count = query().eq("user_id", userId).eq("voucher_id", VoucherOrder.getVoucherId()).count();
            if (count > 0) {
                Result.fail("用户已经购买过一次");
            }
            boolean success = iSeckillVoucherService.update().
                    setSql("stock = stock - 1").
                    eq("voucher_id", VoucherOrder.getVoucherId())
                    .gt("stock", 0)
                    .update();
            if (!success) {
                Result.fail("库存不足");
            }
            save(VoucherOrder);
    }

}
