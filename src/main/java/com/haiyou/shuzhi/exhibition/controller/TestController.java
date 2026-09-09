package com.haiyou.shuzhi.exhibition.controller;

import com.haiyou.shuzhi.exhibition.common.Result;
import com.haiyou.shuzhi.exhibition.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试接口
 *
 * @author exhibition
 * @date 2026-07-26
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final RedisService redisService;

    /**
     * 健康/连通性测试接口
     *
     * @return 统一成功响应
     */
    @GetMapping("/getTest")
    public Result<String> getTest() {
        log.info("getTest 接口被调用，触发成功");
        return Result.ok("触发成功");
    }

    /**
     * Redis 连通性测试（PING）
     *
     * @return PONG
     */
    @GetMapping("/redis")
    public Result<String> redisPing() {
        String pong = redisService.ping();
        return Result.ok(pong);
    }
}
