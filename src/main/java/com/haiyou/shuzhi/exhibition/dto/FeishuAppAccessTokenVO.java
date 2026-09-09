package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 自建应用获取 app_access_token 响应数据
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class FeishuAppAccessTokenVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用访问凭证
     */
    private String appAccessToken;

    /**
     * 租户访问凭证
     */
    private String tenantAccessToken;

    /**
     * 过期时间，单位秒
     */
    private Integer expire;
}
