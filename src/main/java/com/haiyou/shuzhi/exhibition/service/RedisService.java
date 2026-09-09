package com.haiyou.shuzhi.exhibition.service;

import java.util.concurrent.TimeUnit;

/**
 * Redis 通用操作
 *
 * @author exhibition
 * @date 2026-07-29
 */
public interface RedisService {

    /**
     * 写入缓存
     *
     * @param key   键
     * @param value 值
     */
    void set(String key, Object value);

    /**
     * 写入缓存并设置过期时间
     *
     * @param key     键
     * @param value   值
     * @param timeout 过期时间
     * @param unit    时间单位
     */
    void set(String key, Object value, long timeout, TimeUnit unit);

    /**
     * 读取缓存
     *
     * @param key 键
     * @return 值
     */
    Object get(String key);

    /**
     * 删除缓存
     *
     * @param key 键
     * @return 是否删除成功
     */
    Boolean delete(String key);

    /**
     * 是否存在 key
     *
     * @param key 键
     * @return 是否存在
     */
    Boolean hasKey(String key);

    /**
     * 设置过期时间
     *
     * @param key     键
     * @param timeout 过期时间
     * @param unit    时间单位
     * @return 是否设置成功
     */
    Boolean expire(String key, long timeout, TimeUnit unit);

    /**
     * 探测 Redis 是否可用（PING）
     *
     * @return PONG 或异常信息
     */
    String ping();
}
