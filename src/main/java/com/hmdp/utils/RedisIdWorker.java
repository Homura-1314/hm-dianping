package com.hmdp.utils;

import lombok.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long SECOND = 1728454140;
    private static final long COUNT_BITS = 32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @SuppressWarnings("null")
    public Long nextId(String keyPrefix){
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = second - SECOND;
        String mdd = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + timestamp + ":" + mdd);
        return timestamp << COUNT_BITS | count;
    }

}
