package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 已标准化的审批结果。飞书轮询和 EAD 回调都转换为该模型后再处理。
 */
@Data
public class ApprovalStatusSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    private String approvalInstanceId;
    private String businessUniqueKey;
    private String status;
    private String currentNode;
    private String rejectionReason;
    private String approvalTime;
}
