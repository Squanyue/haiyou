package com.haiyou.shuzhi.exhibition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 飞书 token 接口原始响应
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class FeishuTokenResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer code;

    private String msg;

    @JsonProperty("app_access_token")
    private String appAccessToken;

    @JsonProperty("tenant_access_token")
    private String tenantAccessToken;

    private Integer expire;
}
