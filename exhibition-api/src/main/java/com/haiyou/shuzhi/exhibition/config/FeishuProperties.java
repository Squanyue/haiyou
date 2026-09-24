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
    private String baseUrl;

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
    private Long mediaMaxSizeBytes;

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

    /** 默认人员 ID 类型：open_id / union_id / user_id。 */
    private String defaultPersonUserIdType;

    /** RPA 应用类型编码。 */
    private String rpaApplicationTypeCode;

    /**
     * 「人员.直属上级」字段默认用户 ID
     */
    private String defaultSupervisorUserId;

    /**
     * 「人员.工号」字段默认值
     */
    private String defaultPersonEmployeeNo;

    /**
     * 发起流程写入应用索引、类型详情与附件资料时使用的多维表配置。
     */
    private ProcessInstance processInstance = new ProcessInstance();

    @Data
    public static class ProcessInstance {

        /** 发起流程默认使用的飞书 Base app_token。 */
        private String appToken;

        /** 应用索引表 table_id。 */
        private String applicationIndexTableId;

        /** RPA 应用详情表 table_id。 */
        private String rpaDetailTableId;

        /** 海能 work 应用详情表 table_id。 */
        private String hainengWorkDetailTableId;

        /** 附件资料表 table_id。 */
        private String attachmentTableId;
    }

    /**
     * 审批状态回传相关的多维表配置。
     */
    private ApprovalPolling approvalPolling = new ApprovalPolling();

    @Data
    public static class ApprovalPolling {

        /** 总开关；关闭后上架/使用轮询都不跑。 */
        private Boolean enabled;

        /** 上架申请飞书轮询开关；未配置时默认开启。 */
        private Boolean onboardingEnabled = Boolean.TRUE;

        /** 使用申请飞书轮询开关；未配置时默认开启。 */
        private Boolean useEnabled = Boolean.TRUE;

        private Long intervalMs;

        private Long initialDelayMs;

        /** 使用申请轮询间隔；未配置时复用 intervalMs。 */
        private Long useIntervalMs;

        /** 使用申请首次延迟；未配置时复用 initialDelayMs。 */
        private Long useInitialDelayMs;

        /** 审批专用多维表 Base 的 app_token。 */
        private String appToken;

        /** 上架申请表 table_id。 */
        private String tableId;

        /** 使用申请表 table_id。 */
        private String useTableId;

        private String applicationIndexTableId;
        private String userDictionaryTableId;
        private String userPermissionTableId;
        private String notificationTableId;

        /** 应用上架积分相关的多维表配置。 */
        private String pointsBalanceTableId;

        /** 单条飞书审批查询失败时的最大尝试次数。 */
        private Integer queryRetryCount;

        /** 同一审批实例的分布式锁过期时间。 */
        private Long lockTimeoutSeconds;
    }
}
