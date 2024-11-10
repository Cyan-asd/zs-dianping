package com.zsdp.utils;

/**
 * @author cyan
 * @version 1.0
 */
public interface ILock {


    //尝试获取锁  非阻塞
    boolean tryLock(long timeoutSeconds) ;
    //释放锁
    void unLock();


}
