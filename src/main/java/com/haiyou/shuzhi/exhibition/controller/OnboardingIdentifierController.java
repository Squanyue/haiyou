package com.haiyou.shuzhi.exhibition.controller;

import com.haiyou.shuzhi.exhibition.common.Result;
import com.haiyou.shuzhi.exhibition.service.ProcessInstanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 为上架申请提供唯一标识与各表单号预占。
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingIdentifierController {

    private final ProcessInstanceService processInstanceService;

    /**
     * 返回上架申请唯一标识，例如 8f6e7c8d9a234c3b9f1a2e5d6c7b8a90（无 ONB 前缀）。
     *
     * 使用 UUID 生成，不依赖 Redis；接口响应字段保持不变，避免影响前端。
     */
    @GetMapping("/unique-identifier")
    public Result<Map<String, String>> nextUniqueIdentifier() {
        String uniqueIdentifier = UUID.randomUUID().toString().replace("-", "");
        return Result.ok(Collections.singletonMap("uniqueIdentifier", uniqueIdentifier));
    }

    /**
     * 为前端写入的上架申请预占申请单号（PA###）。
     */
    @GetMapping("/application-no")
    public Result<Map<String, String>> reserveApplicationNo(@RequestParam String uniqueIdentifier) {
        String applicationNo = processInstanceService.reserveOnboardingApplicationNo(uniqueIdentifier);
        return Result.ok(Collections.singletonMap("applicationNo", applicationNo));
    }

    /**
     * 为前端写入的应用索引预占应用ID（APP####）。
     */
    @GetMapping("/application-id")
    public Result<Map<String, String>> reserveApplicationId(@RequestParam String uniqueIdentifier) {
        String applicationId = processInstanceService.reserveApplicationId(uniqueIdentifier);
        return Result.ok(Collections.singletonMap("applicationId", applicationId));
    }

    /**
     * 为前端写入的海能 work 详情预占主键（HW####）。
     */
    @GetMapping("/haineng-work-detail-id")
    public Result<Map<String, String>> reserveHainengWorkDetailId(@RequestParam String uniqueIdentifier) {
        String detailId = processInstanceService.reserveHainengWorkDetailId(uniqueIdentifier);
        return Result.ok(Collections.singletonMap("hainengWorkDetailId", detailId));
    }

    /**
     * 海能 work 一次预占三张表单号：申请单号、应用ID、详情主键。
     * 同一唯一标识重复调用返回同一组号码。
     */
    @GetMapping("/haineng-table-codes")
    public Result<Map<String, String>> reserveHainengTableCodes(@RequestParam String uniqueIdentifier) {
        Map<String, String> codes = new LinkedHashMap<String, String>();
        codes.put("applicationNo", processInstanceService.reserveOnboardingApplicationNo(uniqueIdentifier));
        codes.put("applicationId", processInstanceService.reserveApplicationId(uniqueIdentifier));
        codes.put("hainengWorkDetailId", processInstanceService.reserveHainengWorkDetailId(uniqueIdentifier));
        return Result.ok(codes);
    }
}
