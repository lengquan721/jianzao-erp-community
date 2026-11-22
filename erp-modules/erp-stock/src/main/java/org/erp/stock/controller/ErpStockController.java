package org.erp.stock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.erp.stock.domain.bo.StockPage;
import org.erp.stock.domain.bo.StockQuantityBo;
import org.erp.stock.domain.vo.ErpStockAlertTopVo;
import org.erp.stock.domain.vo.ErpStockAlertVo;
import org.erp.stock.domain.vo.ErpStockLogPageVo;
import org.erp.stock.domain.vo.ErpStockQuantityVo;
import org.erp.stock.domain.vo.ErpStockVo;
import org.erp.stock.mapper.ErpStockAlertMapper;
import org.erp.stock.service.ErpStockAlertService;
import org.erp.stock.service.IInventoryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 库存查询 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/erp/stock")
public class ErpStockController extends BaseController {

    private final IInventoryService inventoryService;
    private final ErpStockAlertMapper alertMapper;
    private final ErpStockAlertService alertService;

    @SaCheckPermission("erp:stock:list")
    @GetMapping("/balances")
    public TableDataInfo<ErpStockVo> balances(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String type,
                                             @RequestParam int pageNum,
                                             @RequestParam(defaultValue = StockPage.DEFAULT_SIZE) int pageSize) {
        return inventoryService.pageStock(keyword, type, pageNum, pageSize);
    }

    /** 仅指定产品的全仓数量合计，供选品器和配比回显。 */
    @SaCheckPermission("erp:stock:list")
    @PostMapping("/balances/quantities")
    public R<List<ErpStockQuantityVo>> quantities(@RequestBody StockQuantityBo request) {
        return R.ok(inventoryService.quantityTotals(request.ids()));
    }

    /** 完整预警明细（DOC/25 §3.2），不是看板 TopN。 */
    @SaCheckPermission("erp:stock:list")
    @GetMapping("/alerts")
    public R<List<ErpStockAlertVo>> alerts(@RequestParam(required = false) String type,
                                           @RequestParam(required = false) Long productId) {
        if (type != null && !type.isEmpty() && !"LOW".equals(type) && !"HIGH".equals(type)) {
            throw new ServiceException("type 只能为 LOW 或 HIGH", 400);
        }
        return R.ok(alertMapper.selectAlerts(type, productId));
    }

    @SaCheckPermission("erp:stock:list")
    @GetMapping("/alerts/top")
    public R<ErpStockAlertTopVo> topAlerts(@RequestParam(required = false) Long productId,
                                         @RequestParam(defaultValue = "5") int limit) {
        return R.ok(alertService.top(productId, limit));
    }

    @SaCheckPermission("erp:stock:query")
    @GetMapping("/logs")
    public R<ErpStockLogPageVo> logs(@RequestParam Long productId,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                                    @RequestParam(defaultValue = StockPage.DEFAULT_SIZE) int pageSize,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beforeDate,
                                    @RequestParam(required = false) Long beforeId) {
        return R.ok(inventoryService.pageLogs(productId, startDate, endDate, pageSize, beforeDate, beforeId));
    }

    /** 单产品主仓库存快照。 */
    @SaCheckPermission("erp:stock:query")
    @GetMapping("/balances/{productId}")
    public R<ErpStockVo> balance(@PathVariable Long productId) {
        return R.ok(inventoryService.snapshot(productId));
    }
}
