package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 多维表格删除记录请求
 * 对应飞书：DELETE /open-apis/bitable/v1/apps/:app_token/tables/:table_id/records/:record_id
 */
@Data
public class FeishuRecordDeleteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 多维表格 app_token；不传则使用配置 feishu.app-token */
    private String appToken;

    /** 数据表 table_id；不传则使用配置 feishu.table-id */
    private String tableId;

    /** 记录 ID */
    @NotBlank(message = "recordId 不能为空")
    private String recordId;

    /** 可选：直接传入 access_token；不传则自动获取 tenant_access_token */
    private String accessToken;
}
