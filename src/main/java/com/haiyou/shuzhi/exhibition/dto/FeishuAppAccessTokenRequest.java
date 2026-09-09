package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 自建应用获取 app_access_token 请求体
 * 对应飞书接口：POST /open-apis/auth/v3/app_access_token/internal
 * <p>
 * appId / appSecret 可不传，未传时使用 application.yml 中的 feishu 配置
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class FeishuAppAccessTokenRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 应用唯一标识 App ID
     */
    private String appId;

    /**
     * 应用秘钥 App Secret
     */
    private String appSecret;
}
