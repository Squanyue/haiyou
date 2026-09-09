package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;
import java.util.Map;

/**
 * EAD 审批通过回调请求体
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class EadApprovalCallbackDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务单据号 / 流程业务主键
     */
    @NotBlank(message = "业务单号不能为空")
    private String businessId;

    /**
     * EAD 流程实例 ID
     */
    private String processInstanceId;

    /**
     * 流程定义编码
     */
    private String processCode;

    /**
     * 审批状态，审批通过场景固定为 APPROVED
     */
    @NotBlank(message = "审批状态不能为空")
    private String approvalStatus;

    /**
     * 审批人账号 / 工号
     */
    private String approverId;

    /**
     * 审批人姓名
     */
    private String approverName;

    /**
     * 审批意见
     */
    private String comment;

    /**
     * 审批完成时间，格式：yyyy-MM-dd HH:mm:ss
     */
    private String approvalTime;

    /**
     * 扩展业务字段，透传 EAD 表单数据
     */
    private Map<String, Object> formData;
}
