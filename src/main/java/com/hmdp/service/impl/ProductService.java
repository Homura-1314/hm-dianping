package com.hmdp.service.impl;

import com.hmdp.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOOM_FILTER_NAME;

@Service
@Slf4j
public class ProductService {

    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ShopMapper shopMapper;

    // 1. 初始化布隆过滤器（项目启动时执行）
    @PostConstruct
    public void initBloomFilter() {
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(BLOOM_FILTER_NAME);
        // 初始化：预计放入100万个元素，期望误判率为0.01
        bloomFilter.tryInit(1000000L, 0.01);
        
        // 从数据库加载所有商品ID
        List<Long> productIds = shopMapper.selete();
        log.info("查询所有商品id<UNK>{}", productIds);
        for (Long id : productIds) {
            bloomFilter.add(id);
        }
    }

}