package com.zsdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author cyan
 * @version 1.0
 */
public class SimpleRedisLock implements ILock{

    private  String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;//业务名
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String  KEY_PREFIX = "lock:";
    private static final String  ID_PREFIX = UUID.randomUUID()+"-";//UUID表示锁 避免 误删问题
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT ;//移出锁脚本
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    /**
     * @param timeoutSeconds
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSeconds) {
        //获取线程
        String threadId = ID_PREFIX +Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate
                .opsForValue().setIfAbsent(KEY_PREFIX + name, threadId,
                        timeoutSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//避免拆箱空指针异常 若为空也返回false
    }

    /**
     *
     */

    @Override
    public void unLock() {
        //调用lua脚本 实现原子性
        String threadId = ID_PREFIX +Thread.currentThread().getId();
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),threadId);
    }
}
 /*   @Override //redis锁  非原子性操作 有原子性问题
    public void unLock() {
        //获取锁中的id
        String threadId = ID_PREFIX +Thread.currentThread().getId();
        String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (value.equals(threadId)){//id一致再删除锁
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
