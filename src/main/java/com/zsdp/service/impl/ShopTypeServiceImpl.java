package com.zsdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.zsdp.dto.Result;
import com.zsdp.entity.ShopType;
import com.zsdp.mapper.ShopTypeMapper;
import com.zsdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

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

    private final StringRedisTemplate stringRedisTemplate;

    public ShopTypeServiceImpl(@Qualifier("stringRedisTemplate") StringRedisTemplate stringRedisTemplate, StringRedisTemplate stringRedisTemplate1) {
        this.stringRedisTemplate = stringRedisTemplate1;
    }

    /**
     * zset实现redis缓存
     *
     * @return
     */
    @Override
    public Result queryShopTypeZset() {

        String key ="Cache:ShopType:Zset";


        //先在redis中查询是否存在
        Set<String> set = stringRedisTemplate.opsForZSet().range(key, 0, -1);

        //存在直接返回
        if(set.size()!=0){
            List<ShopType> list=new ArrayList<>();
            for (String str : set){
                list.add(JSONUtil.toBean(str,ShopType.class));
            }

            return Result.ok(list);
        }
        //不存在到数据库中查询
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //判断是否为空
        if (shopTypes == null || shopTypes.isEmpty()) {
            // 3.1.数据库中也不存在，则返回 false
            return Result.fail("分类不存在！");
        }

        //保存到redis
        for (ShopType shopType : shopTypes) {
            stringRedisTemplate.opsForZSet().add(key,JSONUtil.toJsonStr(shopType),shopType.getSort());
        }
        //返回
        return Result.ok(shopTypes);
    }
}
