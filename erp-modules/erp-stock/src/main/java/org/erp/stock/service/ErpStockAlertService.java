package org.erp.stock.service;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.erp.stock.domain.vo.ErpStockAlertTopVo;
import org.erp.stock.mapper.ErpStockAlertMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ErpStockAlertService {

    private final ErpStockAlertMapper mapper;

    /** SQL 已分别截取 LOW/HIGH，并在同一查询中算出各自的未截断总数。 */
    public ErpStockAlertTopVo top(Long productId, int limit) {
        if (limit < 1 || limit > 50) {
            throw new ServiceException("limit 必须在 1–50 之间", 400);
        }
        long lowCount = 0;
        long highCount = 0;
        List<ErpStockAlertTopVo.Item> lows = new ArrayList<>();
        List<ErpStockAlertTopVo.Item> highs = new ArrayList<>();
        for (var row : mapper.selectTopAlerts(productId, limit)) {
            var item = new ErpStockAlertTopVo.Item(row.getProductId(), row.getProductName(), row.getTotalQty(), row.getGap());
            if ("LOW".equals(row.getAlertType())) {
                lowCount = row.getAlertCount();
                lows.add(item);
            } else {
                highCount = row.getAlertCount();
                highs.add(item);
            }
        }
        return new ErpStockAlertTopVo(lowCount, highCount, lows, highs);
    }
}
