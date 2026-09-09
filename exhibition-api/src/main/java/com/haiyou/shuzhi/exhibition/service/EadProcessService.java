package com.haiyou.shuzhi.exhibition.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * EAD 流程服务
 *
 * @author exhibition
 * @date 2026-07-30
 */
public interface EadProcessService {

    /**
     * 发起 EAD 流程实例（转发前端参数）
     *
     * @param userAccount 用于获取令牌的用户账号
     * @param formJson    流程表单 JSON，例如 {"inputs":[...],"sysAndFlowCode":"test_ztcs"}
     * @param files       附件，可为空，支持多个（同名 file）
     * @return EAD 原始响应
     */
    Object startProcess(String userAccount, String formJson, MultipartFile[] files);
}
