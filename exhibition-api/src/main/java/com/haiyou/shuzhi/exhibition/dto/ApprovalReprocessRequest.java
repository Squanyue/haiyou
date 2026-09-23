package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 手动重放 EAD/飞书审批结果，用于回调后飞书写表中途失败的续写。
 */
@Data
public class ApprovalReprocessRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 上架申请唯一标识 ONB…；与 processInstanceId 至少传一个。 */
    private String bizUniqueKey;

    /** 审批实例 ID（EAD instId 或飞书实例号）。 */
    private String processInstanceId;

    /**
     * 审批状态。不传时默认按「结束/已通过」续写。
     * 可传：结束、中止、流转中、已通过、APPROVED、已退回、REJECTED 等。
     */
    private String approvalStatus;

    /** 当前审批节点文案，可选。 */
    private String currentNode;

    /** 退回原因，可选。 */
    private String rejectionReason;

    /**
     * true：不要求申请仍是「审批中」，已通过也可再跑一遍幂等续写。
     * false：只处理仍为「审批中」的申请（与正常回调一致）。
     */
    private Boolean force;
}
