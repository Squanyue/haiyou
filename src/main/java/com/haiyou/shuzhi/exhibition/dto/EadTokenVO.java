package com.haiyou.shuzhi.exhibition.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * EAD 令牌响应
 *
 * @author exhibition
 * @date 2026-07-29
 */
@Data
public class EadTokenVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("expires_in")
    private Object expiresIn;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("expires_refresh")
    private Object expiresRefresh;
}
