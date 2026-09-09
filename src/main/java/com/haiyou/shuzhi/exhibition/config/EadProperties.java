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
    private String tokenUrl = "https://wzgyl.uatead.cnooc/api/we-open/v1/wethirdpartysystemlogin/getToken";

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
    private String processStartUrl =
            "http://wzgyl.uatead.cnooc/xcoa/api/framework/v1/extra-process-drive/process-instance/start";

    /**
     * 发起流程时 JSON 表单字段名（Apipost 中为 createFlowInstance）
     */
    private String processStartJsonField = "createFlowInstance";

    /**
     * EAD 回调附件本地保存目录
     */
    private String callbackFileDir = "D:/haiyou/shuzhi/code/file";
}
