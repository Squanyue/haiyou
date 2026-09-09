package com.haiyou.shuzhi.exhibition.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 飞书素材上传服务
 *
 * @author exhibition
 * @date 2026-08-07
 */
public interface FeishuMediaService {

    /**
     * 上传素材到多维表格，返回 file_token
     *
     * @param appToken 多维表格 app_token
     * @param file     文件
     * @return file_token
     */
    String uploadBitableMedia(String appToken, MultipartFile file);
}
