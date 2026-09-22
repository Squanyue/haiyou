package com.haiyou.shuzhi.exhibition.controller;

import com.haiyou.shuzhi.exhibition.common.Result;
import com.haiyou.shuzhi.exhibition.service.ApprovalResultProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手动触发飞书审批轮询，便于本地联调。
 */
@Slf4j
@RestController
@RequestMapping("/api/feishu/approval-polling")
@RequiredArgsConstructor
public class FeishuApprovalPollingController {

    private final ApprovalResultProcessor approvalResultProcessor;

    /** 同时触发上架与使用申请轮询。 */
    @PostMapping("/trigger")
    public Result<String> trigger() {
        log.info("手动触发飞书审批轮询（上架+使用）");
        approvalResultProcessor.pollFeishuOnboardingApprovals();
        approvalResultProcessor.pollFeishuUseApprovals();
        return Result.ok("飞书审批轮询已执行");
    }

    @PostMapping("/trigger/onboarding")
    public Result<String> triggerOnboarding() {
        log.info("手动触发飞书上架申请轮询");
        approvalResultProcessor.pollFeishuOnboardingApprovals();
        return Result.ok("飞书上架申请轮询已执行");
    }

    @PostMapping("/trigger/use")
    public Result<String> triggerUse() {
        log.info("手动触发飞书使用申请轮询");
        approvalResultProcessor.pollFeishuUseApprovals();
        return Result.ok("飞书使用申请轮询已执行");
    }
}
