package com.haiyou.shuzhi.exhibition.service;

import com.haiyou.shuzhi.exhibition.dto.ProcessInstanceStartRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 流程发起业务服务
 *
 * @author exhibition
 * @date 2026-07-30
 */
public interface ProcessInstanceService {

    /**
     * 组装参数并调用 EAD 发起流程
     *
     * @param request 前端入参
     * @param files   附件，可为空，支持多个
     * @return EAD 响应
     */
    Object start(ProcessInstanceStartRequest request, MultipartFile[] files);

    /**
     * 组装参数并调用 EAD 发起流程，保留附件在 EAD 表单中的字段名。
     *
     * @param request 前端入参
     * @param files 所有附件，用于落库
     * @param eadFilesByField EAD 附件字段名到文件列表的映射
     * @return EAD 响应
     */
    default Object start(ProcessInstanceStartRequest request,
                         MultipartFile[] files,
                         Map<String, MultipartFile[]> eadFilesByField) {
        return start(request, files);
    }

    /**
     * 预占上架申请单号。重复传入同一唯一标识时，必须返回同一个申请单号。
     */
    String reserveOnboardingApplicationNo(String uniqueIdentifier);

    /**
     * 预占应用索引应用ID。重复传入同一唯一标识时，必须返回同一个应用ID。
     */
    String reserveApplicationId(String uniqueIdentifier);

    /**
     * 预占海能 work 应用详情主键。重复传入同一唯一标识时，必须返回同一个主键。
     */
    String reserveHainengWorkDetailId(String uniqueIdentifier);
}
