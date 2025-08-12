package com.hmdp.utils;


import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import com.hmdp.dto.UserDTO;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @SuppressWarnings("null")
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return true;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.DAYS);
        return true;
    }

    @SuppressWarnings("null")
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}

