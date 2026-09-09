package com.haiyou.shuzhi.exhibition.controller;

import com.haiyou.shuzhi.exhibition.common.Result;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordCreateVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordSearchVO;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateRequest;
import com.haiyou.shuzhi.exhibition.dto.FeishuRecordUpdateVO;
import com.haiyou.shuzhi.exhibition.service.FeishuBitableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 飞书多维表格接口
 *
 * @author exhibition
 * @date 2026-07-23
 */
@Slf4j
@RestController
@RequestMapping("/api/feishu/bitable")
@RequiredArgsConstructor
public class FeishuBitableController {

    private final FeishuBitableService feishuBitableService;

    /**
     * 查询多维表格记录
     *
     * @param request 查询条件
     * @return 查询结果
     */
    @PostMapping("/records/search")
    public Result<FeishuRecordSearchVO> searchRecords(@Validated @RequestBody FeishuRecordSearchRequest request) {
        log.info("查询飞书多维表格记录, appToken={}, tableId={}", request.getAppToken(), request.getTableId());
        FeishuRecordSearchVO vo = feishuBitableService.searchRecords(request);
        return Result.ok(vo);
    }

    /**
     * 新增多维表格记录
     * <p>
     * 对应飞书文档：POST /open-apis/bitable/v1/apps/:app_token/tables/:table_id/records
     *
     * @param request 新增请求
     * @return 新增后的记录
     */
    @PostMapping("/records")
    public Result<FeishuRecordCreateVO> createRecord(@Validated @RequestBody FeishuRecordCreateRequest request) {
        log.info("新增多维表格记录, appToken={}, tableId={}", request.getAppToken(), request.getTableId());
        FeishuRecordCreateVO vo = feishuBitableService.createRecord(request);
        return Result.ok(vo);
    }

    /**
     * 更新多维表格记录
     * <p>
     * 对应飞书文档：PUT /open-apis/bitable/v1/apps/:app_token/tables/:table_id/records/:record_id
     * https://open.feishu.cn/document/server-docs/docs/bitable-v1/app-table-record/update
     *
     * @param request 更新请求
     * @return 更新后的记录
     */
    @PutMapping("/records/update")
    public Result<FeishuRecordUpdateVO> updateRecord(@Validated @RequestBody FeishuRecordUpdateRequest request) {
        log.info("更新飞书多维表格记录, appToken={}, tableId={}, recordId={}",
                request.getAppToken(), request.getTableId(), request.getRecordId());
        FeishuRecordUpdateVO vo = feishuBitableService.updateRecord(request);
        return Result.ok(vo);
    }
}
