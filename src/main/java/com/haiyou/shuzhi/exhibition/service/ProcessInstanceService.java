package com.haiyou.shuzhi.exhibition.service;

import com.haiyou.shuzhi.exhibition.dto.ProcessInstanceStartRequest;
import org.springframework.web.multipart.MultipartFile;

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
}
