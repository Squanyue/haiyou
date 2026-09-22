package com.haiyou.shuzhi.exhibition.job;

import com.haiyou.shuzhi.exhibition.service.ApprovalResultProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 飞书审批轮询：上架申请与使用申请分任务执行，互不影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuApprovalPollingJob {

    private final ApprovalResultProcessor approvalResultProcessor;

    @Scheduled(
            fixedDelayString = "${feishu.approval-polling.interval-ms}",
            initialDelayString = "${feishu.approval-polling.initial-delay-ms}")
    public void pollOnboarding() {
        try {
            approvalResultProcessor.pollFeishuOnboardingApprovals();
        } catch (Exception ex) {
            log.error("飞书上架申请轮询任务执行失败", ex);
        }
    }

    @Scheduled(
            fixedDelayString = "${feishu.approval-polling.use-interval-ms:${feishu.approval-polling.interval-ms}}",
            initialDelayString = "${feishu.approval-polling.use-initial-delay-ms:${feishu.approval-polling.initial-delay-ms}}")
    public void pollUse() {
        try {
            approvalResultProcessor.pollFeishuUseApprovals();
        } catch (Exception ex) {
            log.error("飞书使用申请轮询任务执行失败", ex);
        }
    }
}
