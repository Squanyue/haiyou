package com.haiyou.shuzhi.exhibition.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * EAD 开放接口配置
 *
 * @author exhibition
 * @date 2026-07-29
 */
@Data
@Component
@ConfigurationProperties(prefix = "ead")
public class EadProperties {

    /**
     * 获取令牌完整地址
     */
    private String tokenUrl;

    /**
     * 第三方系统 appId
     */
    private String appId;

    /**
     * 第三方系统 appSecret
     */
    private String appSecret;

    /**
     * 发起流程完整地址
     */
    private String processStartUrl;

    /**
     * 前端未传 userAccount 时使用的 EAD 账号。
     */
    private String defaultUserAccount;

    /**
     * 前端未传流程编码时使用的 EAD 流程编码。
     */
    private String defaultSysAndFlowCode;

    /**
     * RPA 应用上架审批使用的 EAD 流程编码。
     */
    private String rpaSysAndFlowCode;

    /**
     * EAD 回调附件本地保存目录
     */
    private String callbackFileDir;
}
