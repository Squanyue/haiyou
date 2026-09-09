package com.haiyou.shuzhi.exhibition.controller;

import com.haiyou.shuzhi.exhibition.common.Result;
import com.haiyou.shuzhi.exhibition.dto.FeishuAppAccessTokenRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuAppAccessTokenVO;
import com.haiyou.shuzhi.exhibition.service.FeishuAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 飞书认证接口
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Slf4j
@RestController
@RequestMapping("/api/feishu/auth")
@RequiredArgsConstructor
public class FeishuAuthController {

    private final FeishuAuthService feishuAuthService;

    /**
     * 自建应用获取 app_access_token
     * <p>
     * 对应飞书文档：POST /open-apis/auth/v3/app_access_token/internal
     *
     * @param request 可选；未传 appId/appSecret 时使用配置文件
     * @return token 信息
     */
    @PostMapping("/app-access-token")
    public Result<FeishuAppAccessTokenVO> getAppAccessToken(
            @RequestBody(required = false) FeishuAppAccessTokenRequest request) {
        log.info("获取飞书 app_access_token");
        FeishuAppAccessTokenVO vo = feishuAuthService.getAppAccessToken(request);
        return Result.ok(vo);
    }
}
