package com.haiyou.shuzhi.exhibition.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * EAD 回调附件落盘服务
 *
 * @author exhibition
 * @date 2026-08-07
 */
public interface EadCallbackFileService {

    /**
     * 将回调附件保存到本地目录
     *
     * @param filesByField 字段名 -> 文件列表（同名 file 可多份）
     * @return 已保存文件信息（字段名、原名、本地路径、大小）
     */
    List<Map<String, Object>> saveCallbackFiles(Map<String, List<MultipartFile>> filesByField);
}
