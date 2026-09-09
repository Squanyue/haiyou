package com.haiyou.shuzhi.exhibition.common;

/**
 * 业务错误码常量
 * 格式：{来源}{四位编号}，A=用户端，B=系统，C=第三方
 *
 * @author exhibition
 * @date 2026-07-23
 */
public final class ErrorCode {

    public static final String SUCCESS = "00000";
    public static final String USER_ERROR = "A0001";
    public static final String PARAM_ERROR = "A0300";
    public static final String RESOURCE_NOT_FOUND = "A0400";
    public static final String SYSTEM_ERROR = "B0001";
    public static final String THIRD_PARTY_ERROR = "C0001";

    private ErrorCode() {
    }
}
