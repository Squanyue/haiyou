package com.haiyou.shuzhi.exhibition.controller;

import com.haiyou.shuzhi.exhibition.common.Result;
import com.haiyou.shuzhi.exhibition.service.ProcessInstanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * 为上架申请提供后端生成的唯一标识。
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingIdentifierController {

    private final ProcessInstanceService processInstanceService;

    /**
     * 返回上架申请唯一标识，例如 ONB8f6e7c8d9a234c3b9f1a2e5d6c7b8a90。
     *
     * 使用 UUID 生成，不依赖 Redis；接口响应字段保持不变，避免影响前端。
     */
    @GetMapping("/unique-identifier")
    public Result<Map<String, String>> nextUniqueIdentifier() {
        String uniqueIdentifier = "ONB" + UUID.randomUUID().toString().replace("-", "");
        return Result.ok(Collections.singletonMap("uniqueIdentifier", uniqueIdentifier));
    }

    /**
     * 为前端写入的海能 work 上架申请预占申请单号。
     */
    @GetMapping("/application-no")
    public Result<Map<String, String>> reserveApplicationNo(@RequestParam String uniqueIdentifier) {
        String applicationNo = processInstanceService.reserveOnboardingApplicationNo(uniqueIdentifier);
        return Result.ok(Collections.singletonMap("applicationNo", applicationNo));
    }
}
