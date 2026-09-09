package com.haiyou.shuzhi.exhibition.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 飞书开放平台配置
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
@Component
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    /**
     * 飞书开放平台地址
     */
    private String baseUrl = "https://open.feishu.cn";

    /**
     * 应用 App ID
     */
    private String appId;

    /**
     * 应用 App Secret
     */
    private String appSecret;

    /**
     * 默认多维表格 app_token（可被请求参数覆盖）
     */
    private String appToken;

    /**
     * 默认数据表 table_id（可被请求参数覆盖）
     */
    private String tableId;

    /**
     * 上传到多维表格的单个附件大小上限（字节），默认约 2GB-1（飞书多维表附件上限）
     */
    private Long mediaMaxSizeBytes = 2147483647L;

    /**
     * 前端未上传视频时使用的本地默认视频文件
     */
    private String defaultVideoFile;

    /**
     * 前端未上传视频时优先复用的飞书视频素材 token
     */
    private String defaultVideoFileToken;

    /**
     * 新增记录时「人员」字段默认用户 ID（未传 personUserId 时使用）
     */
    private String defaultPersonUserId;

    /**
     * 默认人员 ID 类型：open_id / union_id / user_id
     */
    private String defaultPersonUserIdType = "open_id";

    /**
     * 「人员.直属上级」字段默认用户 ID
     */
    private String defaultSupervisorUserId;

    /**
     * 「人员.工号」字段默认值
     */
    private String defaultPersonEmployeeNo;
}
