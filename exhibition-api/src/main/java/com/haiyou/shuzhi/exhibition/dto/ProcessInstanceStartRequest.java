package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 前端发起流程请求
 *
 * @author exhibition
 * @date 2026-07-30
 */
@Data
public class ProcessInstanceStartRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 多维表格 tableId
     */
    private String tableId;

    /**
     * 多维表格 app_token；不传时使用应用上架默认 Base。
     */
    private String appToken;

    /**
     * 多维表格记录 ID
     */
    private String recordId;

    /**
     * 上架申请业务唯一标识；同时写入应用索引，并作为 EAD bizUniqueKey。
     */
    private String uniqueIdentifier;

    /**
     * 流程标题
     */
    private String title;

    /**
     * EAD 流程编码。RPA（T003）由后端强制使用 ead.rpa-sys-and-flow-code；
     * 其它类型前端为空时用 ead.default-sys-and-flow-code。
     */
    private String sysAndFlowCode;

    /**
     * 用户账号（用于获取 EAD token），前端为空时默认 linmm
     */
    private String userAccount;

    /**
     * 多维表「人员」字段要写入的用户 ID（open_id / user_id / union_id）
     * 可传单个；多人请用 personUserIds
     */
    private String personUserId;

    /**
     * 多维表「人员」字段要写入的用户 ID 列表（优先于 personUserId）
     */
    private java.util.List<String> personUserIds;

    /**
     * 人员 ID 类型：open_id / union_id / user_id，默认 open_id
     */
    private String personUserIdType;

    /**
     * EAD 审批部门编码（前端选择值转换后传入）
     */
    private String approverDepartment;

    /**
     * EAD 抄送部门编码（前端选择值转换后传入）
     */
    private String ccDepartment;

    /**
     * EAD 流程表单输入，key 使用 EAD 流程定义的英文参数名。
     */
    private Map<String, Object> eadInputs;

    /**
     * 实际写入飞书多维表的字段值，key 使用飞书表字段名。
     */
    private Map<String, Object> fields;

    /**
     * 应用类型详情表字段值，key 使用详情表字段名；没有对应值时可不传。
     */
    private Map<String, Object> detailFields;

}
