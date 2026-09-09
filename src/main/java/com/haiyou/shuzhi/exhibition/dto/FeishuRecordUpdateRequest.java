package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Map;

/**
 * 多维表格更新记录请求
 * 对应飞书：PUT /open-apis/bitable/v1/apps/:app_token/tables/:table_id/records/:record_id
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class FeishuRecordUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 多维表格 app_token；不传则使用配置 feishu.app-token
     */
    private String appToken;

    /**
     * 数据表 table_id；不传则使用配置 feishu.table-id
     */
    private String tableId;

    /**
     * 记录 ID
     */
    @NotBlank(message = "recordId 不能为空")
    private String recordId;

    /**
     * 用户 ID 类型：open_id / union_id / user_id
     */
    private String userIdType;

    /**
     * 是否忽略一致性读写检查
     */
    private Boolean ignoreConsistencyCheck;

    /**
     * 可选：直接传入 access_token；不传则自动获取 tenant_access_token
     */
    private String accessToken;

    /**
     * 要更新的字段（增量更新，仅更新传入字段；字段值设为 null 可置空）
     */
    @NotNull(message = "fields 不能为空")
    private Map<String, Object> fields;
}
