package com.zsdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zsdp.dto.Result;
import com.zsdp.entity.Shop;
import com.zsdp.mapper.ShopMapper;
import com.zsdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zsdp.utils.CacheClient;
import com.zsdp.utils.RedisConstants;
import com.zsdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.zsdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.zsdp.utils.RedisConstants.CACHE_SHOP_TTL;

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

    String qkey="Cache:Shop:";
    @Autowired
    private CacheClient cacheClient;
    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //上锁
    private Boolean tryLock(String key){

        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }
    //解锁
    private void unLock(String key){

        stringRedisTemplate.delete(key);

    }
    //保存封装shop数据到redis
    private void saveShop2Redis(Long id,Long expireSeconds){
        //获取商铺数据
        Shop shop =queryWithPassThrough(id);
        //封装到redisDate
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        //设置逻辑过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //保存到Redis
        stringRedisTemplate.opsForValue().set(qkey+id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * @param shop
     * @return
     */
    @Transactional
    @Override
    public Result updateByIdC(Shop shop) {

        Long id = shop.getId();

        if (id == null) {
            return Result.fail("商户id不能为空");
        }

        String   key ="Cache:Shop:"+id;

        //修改数据库
        updateById(shop);

        //删除缓存
        stringRedisTemplate.delete(key);

        return Result.ok();
    }

    /**
     * 查询商铺
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop =queryWithPassThrough(id);
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //缓存击穿
        //互斥锁
        //Shop shop = queryWithMutex(id);
        //逻辑过期
        //Shop shop =queryWithLogic(id);


        if(shop ==null){
            return Result.fail("商铺不存在！");
        }

        return Result.ok(shop);
    }
    /**
     * 查询商铺  逻辑删除实现防止缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithLogic(Long id) {

        String lockKey ="Lock:Shop:"+id;
        String key =qkey+id;
        //查询redis中是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在，返回null
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return shop;
        }
        //过期 缓存重建
        Boolean flag = tryLock(lockKey);
        if (flag) {//获取到锁
            CACHE_REBUILD_EXECUTOR.submit( ()->{

                try{
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }
            });
        }

        return shop;
    }
    /**
     * 查询商铺  互斥锁实现防止缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {

        String lockKey ="Lock:Shop:"+id;
        String key =qkey+id;
        //查询redis中是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //存在则返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //命中空值
        if (shopJson != null) {
            return null;
        }
        //逻辑过期
        //
        //先获取锁
        Shop shop = null;
        try {
            Boolean flag = tryLock(lockKey);
            if(!flag){//获取失败
                Thread.sleep(50);//睡眠
                return queryWithMutex(id);
            }
            //获取锁成功
            //redis不存在到数据库中查找
            shop =getById(id);
            //如果数据库不存在报错
            if(shop == null){//缓存空值防止缓存击穿
                stringRedisTemplate.opsForValue().set(key,"",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //查找到后保存到redis

            stringRedisTemplate.opsForValue().set(lockKey,JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL, TimeUnit.MINUTES);
            //返回

        } catch (Exception e) {
            throw new RuntimeException(e);
        }finally{
            unLock(lockKey);//释放互斥锁
        }
        return shop;
    }
    /**
     * 查询商铺  互斥锁实现防止缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {

        String key =qkey+id;

        //查询redis中是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //存在则返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //命中空值
        if (shopJson != null) {
            return null;
        }

        //不存在到数据库中查找
        Shop shop =getById(id);
        //如果不存在报错
        if(shop == null){//缓存空值防止缓存击穿
            stringRedisTemplate.opsForValue().set(key,"",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //查找到后保存到redis

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return shop;
    }
}
