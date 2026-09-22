package com.haiyou.shuzhi.exhibition.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /**
     * 按 EAD 流程表单的附件参数名发起流程。
     */
    default Object startProcess(String userAccount,
                                String formJson,
                                Map<String, MultipartFile[]> filesByField) {
        List<MultipartFile> files = new ArrayList<MultipartFile>();
        if (filesByField != null) {
            for (MultipartFile[] values : filesByField.values()) {
                if (values == null) {
                    continue;
                }
                for (MultipartFile file : values) {
                    if (file != null && !file.isEmpty()) {
                        files.add(file);
                    }
                }
            }
        }
        return startProcess(userAccount, formJson, files.toArray(new MultipartFile[files.size()]));
    }
}
