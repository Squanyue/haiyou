package com.haiyou.shuzhi.exhibition.service;

import com.haiyou.shuzhi.exhibition.dto.ApprovalHandlingResult;
import com.haiyou.shuzhi.exhibition.dto.ApprovalStatusSnapshot;

/**
 * 统一处理飞书轮询和 EAD 回调得到的审批结果。
 */
public interface ApprovalResultProcessor {

    /**
     * 处理指定来源的一个审批结果（仅处理仍为「审批中」的申请）。
     */
    ApprovalHandlingResult process(String approvalSource, ApprovalStatusSnapshot snapshot);

    /**
     * 处理指定来源的一个审批结果。
     *
     * @param force true 时即使申请已不是「审批中」也会按幂等逻辑续写权限/积分/通知
     */
    ApprovalHandlingResult process(String approvalSource, ApprovalStatusSnapshot snapshot, boolean force);

    /**
     * 轮询仍处于审批中的飞书上架申请。
     */
    void pollFeishuOnboardingApprovals();

    /**
     * 轮询仍处于审批中的飞书使用申请。
     */
    void pollFeishuUseApprovals();
}
