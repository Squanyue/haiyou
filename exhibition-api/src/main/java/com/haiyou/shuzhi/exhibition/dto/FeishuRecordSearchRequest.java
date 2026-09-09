package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.io.Serializable;
import java.util.List;

/**
 * 多维表格查询记录请求
 * 对应飞书：POST /open-apis/bitable/v1/apps/:app_token/tables/:table_id/records/search
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class FeishuRecordSearchRequest implements Serializable {

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
     * 用户 ID 类型：open_id / union_id / user_id，默认 open_id
     */
    private String userIdType;

    /**
     * 分页标记
     */
    private String pageToken;

    /**
     * 分页大小，最大 500，默认 20
     */
    @Min(value = 1, message = "pageSize 最小为 1")
    @Max(value = 500, message = "pageSize 最大为 500")
    private Integer pageSize;

    /**
     * 可选：直接传入 access_token；不传则自动获取 tenant_access_token
     */
    private String accessToken;

    /**
     * 视图 ID
     */
    private String viewId;

    /**
     * 返回字段名列表
     */
    private List<String> fieldNames;

    /**
     * 排序条件
     */
    @Valid
    private List<SortItem> sort;

    /**
     * 筛选条件
     */
    @Valid
    private FilterInfo filter;

    /**
     * 是否返回创建/修改人及时间等自动字段
     */
    private Boolean automaticFields;

    @Data
    public static class SortItem implements Serializable {

        private static final long serialVersionUID = 1L;

        private String fieldName;

        private Boolean desc;
    }

    @Data
    public static class FilterInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * and / or
         */
        private String conjunction;

        @Valid
        private List<Condition> conditions;
    }

    @Data
    public static class Condition implements Serializable {

        private static final long serialVersionUID = 1L;

        private String fieldName;

        private String operator;

        private List<String> value;
    }
}
