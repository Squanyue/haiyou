package com.haiyou.shuzhi.exhibition.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 响应结构
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private String code;
    private String message;
    private T data;
    private Long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<T>();
        result.setCode(ErrorCode.SUCCESS);
        result.setMessage("操作成功");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> ok(String message, T data) {
        Result<T> result = new Result<T>();
        result.setCode(ErrorCode.SUCCESS);
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> fail(String message) {
        return fail(ErrorCode.USER_ERROR, message);
    }

    public static <T> Result<T> fail(String code, String message) {
        Result<T> result = new Result<T>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(null);
        return result;
    }
}
