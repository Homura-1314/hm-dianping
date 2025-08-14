package com.hmdp.service.impl;

import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import static com.hmdp.utils.RedisConstants.BLOOM_FILTER_NAME;
import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryByid(Long id) {
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_NAME);
        if (!bloomFilter.contains(id)) {
            return Result.fail("店铺不存在");
        }
//        Shop query = cacheClient.queryWithPass(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        if (query == null) {
//            return Result.fail("店铺不存在");
//        }

        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        if (shopJson != null) { // 命中空值
            return Result.fail("店铺不存在");
        }

        // --- 使用 Redisson RLock ---
        String lockName = LOCK_SHOP_KEY + id; // 锁的名称
        RLock lock = redissonClient.getLock(lockName); // 获取Redisson的分布式锁对象

        try {
            // 尝试获取锁，最多等待 10 秒，锁的过期时间为 60 秒
            // 这个方法会阻塞当前线程，直到获取到锁或者超时
            boolean isLock = lock.tryLock(10, TimeUnit.SECONDS); // 尝试获取锁，等待10秒

            if (!isLock) {
                // 未能获取到锁，直接返回繁忙信息，避免无限等待和重试
                return Result.fail("系统繁忙，请稍后再试");
            }

            // --- 成功获取到锁后，执行双重检查和数据库查询 ---
            // 双重检查：在获取锁后再次检查缓存
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)) {
                Shop shopTemp = JSONUtil.toBean(shopJson, Shop.class);
                return Result.ok(shopTemp);
            }
            if (shopJson != null) { // 命中空值
                return Result.fail("店铺不存在");
            }

            // 缓存中没有，查询数据库
            Shop shop = getById(id);

            // 数据库中不存在
            if (shop == null){
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",
                        CACHE_NULL_TTL + RandomUtil.randomLong(LOCK_SHOP_TTL), TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }

            // 数据库中存在
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL + RandomUtil.randomLong(LOCK_SHOP_TTL), TimeUnit.MINUTES);

            return Result.ok(shop);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断状态
            throw new RuntimeException("获取锁时线程中断", e);
        } finally {
            // 确保锁被释放，Redisson的unlock是安全的，只有持有锁的线程才能释放
            if (lock.isLocked() && lock.isHeldByCurrentThread()) { // 避免误删其他线程的锁
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺不存在");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

}
