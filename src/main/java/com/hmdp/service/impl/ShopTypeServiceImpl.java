package com.hmdp.service.impl;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LABEL;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopTypeMapper shopTypeMapper;


    @Override
    public Result queryList() {
        String shopLable = stringRedisTemplate.opsForValue().get(CACHE_SHOP_LABEL);
        if (StrUtil.isNotBlank(shopLable)) {
            return Result.ok(JSONUtil.toList(shopLable, ShopType.class));
        }
        List<ShopType> shopTypes = shopTypeMapper.selectLabel();
        if (shopTypes.isEmpty()) {
            return Result.fail("标签不存在");
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_LABEL, JSONUtil.toJsonStr(shopTypes));
        return Result.ok(shopTypes);
    }
}
