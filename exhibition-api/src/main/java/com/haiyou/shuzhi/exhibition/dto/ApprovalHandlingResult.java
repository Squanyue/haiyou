package com.haiyou.shuzhi.exhibition.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 审批结果处理结果，用于 EAD 回调日志和响应。
 */
@Data
public class ApprovalHandlingResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean processed;
    private String message;
    private String requestType;
    private String requestRecordId;
}
