package org.erp.fin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.erp.fin.domain.vo.FinArApSummaryVo;
import org.erp.fin.domain.vo.FinArApSummaryPageVo;
import org.erp.fin.domain.vo.FinArApVo;
import org.erp.fin.service.IFinArApService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应收应付台账
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/finance/ar-ap")
public class FinArApController extends BaseController {

    private final IFinArApService arApService;

    /**
     * 台账分页
     */
    @SaCheckPermission("erp:finance:list")
    @GetMapping("/list")
    public TableDataInfo<FinArApVo> list(@RequestParam(required = false) String partyType,
                                         @RequestParam(required = false) Long partyId,
                                         @RequestParam(required = false) String status,
                                         PageQuery pageQuery) {
        return arApService.selectPage(partyType, partyId, status, pageQuery);
    }

    /**
     * 兼容旧汇总：完整返回至多 5000 个往来单位，超限明确拒绝；新 UI 使用 /summary/page。
     */
    @SaCheckPermission("erp:finance:list")
    @GetMapping("/summary")
    public R<FinArApSummaryVo> summary(@RequestParam String partyType) {
        return R.ok(arApService.summary(partyType));
    }

    /** 全部匹配台账的金额合计 + 数据库分页的往来单位分组。 */
    @SaCheckPermission("erp:finance:list")
    @GetMapping("/summary/page")
    public R<FinArApSummaryPageVo> summaryPage(@RequestParam String partyType,
                                             @RequestParam(defaultValue = "1") int pageNum,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(arApService.summaryPage(partyType, pageNum, pageSize));
    }

    /**
     * 兼容旧明细；收/付款表单可显式只取可核销候选。不改变权限或返回形状。
     */
    @SaCheckPermission("erp:finance:list")
    @GetMapping("/detail")
    public R<List<FinArApVo>> detail(@RequestParam String partyType, @RequestParam Long partyId,
                                     @RequestParam(defaultValue = "false") boolean allocatableOnly) {
        return R.ok(allocatableOnly ? arApService.listAllocatableByParty(partyType, partyId)
            : arApService.listByParty(partyType, partyId));
    }

    /** 按往来单位明细：数据库分页，金额/日期/状态字段与旧 detail 一致。 */
    @SaCheckPermission("erp:finance:list")
    @GetMapping("/detail/page")
    public TableDataInfo<FinArApVo> detailPage(@RequestParam String partyType, @RequestParam Long partyId,
                                             @RequestParam(defaultValue = "1") int pageNum,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        return arApService.pageByParty(partyType, partyId, pageNum, pageSize);
    }
}
