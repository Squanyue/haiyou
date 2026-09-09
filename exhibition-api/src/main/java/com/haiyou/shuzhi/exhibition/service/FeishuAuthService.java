package com.haiyou.shuzhi.exhibition.service;

import com.haiyou.shuzhi.exhibition.dto.FeishuAppAccessTokenRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuAppAccessTokenVO;

/**
 * 飞书认证服务
 *
 * @author exhibition
 * @date 2026-07-23
 */
public interface FeishuAuthService {

    /**
     * 自建应用获取 app_access_token
     *
     * @param request 请求参数，可为 null；未传 appId/appSecret 时使用配置
     * @return token 信息
     */
    FeishuAppAccessTokenVO getAppAccessToken(FeishuAppAccessTokenRequest request);

    /**
     * 获取 tenant_access_token（多维表格等 OpenAPI 常用）
     *
     * @return tenant_access_token
     */
    String getTenantAccessToken();
}
