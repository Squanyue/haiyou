package com.haiyou.shuzhi.exhibition.service;

import com.haiyou.shuzhi.exhibition.dto.ApprovalStatusSnapshot;

/**
 * 飞书审批实例查询服务。
 */
public interface FeishuApprovalService {

    /**
     * 按审批实例 ID 查询最新审批状态。
     */
    ApprovalStatusSnapshot queryInstance(String approvalInstanceId);
}
