package org.erp.master.service;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.erp.master.domain.bo.MasterOptionQuery;
import org.erp.master.domain.vo.ErpCustomerVo;
import org.erp.master.domain.vo.ErpProductCategoryVo;
import org.erp.master.domain.vo.ErpProductVo;
import org.erp.master.domain.vo.ErpSupplierVo;
import org.erp.master.domain.vo.ErpWarehouseVo;
import org.erp.master.domain.vo.MasterOptionPageVo;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductCategoryMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.master.mapper.ErpWarehouseMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 专用于远程选项；不截断给 Excel 使用的原 selectList/selectPage。 */
@Service
@RequiredArgsConstructor
public class MasterOptionService {
    private final ErpProductMapper productMapper;
    private final ErpCustomerMapper customerMapper;
    private final ErpSupplierMapper supplierMapper;
    private final ErpWarehouseMapper warehouseMapper;
    private final ErpProductCategoryMapper categoryMapper;

    public MasterOptionPageVo<ErpProductVo> products(MasterOptionQuery query, String type, Long categoryId) {
        type = type == null || type.isBlank() ? null : type.strip();
        if (type != null && !"PRODUCT".equals(type) && !"MATERIAL".equals(type)) {
            throw new ServiceException("type 只能为 PRODUCT 或 MATERIAL", 400);
        }
        return page(query, productMapper.selectOptions(query, type, categoryId));
    }

    public MasterOptionPageVo<ErpCustomerVo> customers(MasterOptionQuery query) {
        return page(query, customerMapper.selectOptions(query));
    }

    public MasterOptionPageVo<ErpSupplierVo> suppliers(MasterOptionQuery query) {
        return page(query, supplierMapper.selectOptions(query));
    }

    public MasterOptionPageVo<ErpWarehouseVo> warehouses(MasterOptionQuery query) {
        return page(query, warehouseMapper.selectOptions(query));
    }

    public MasterOptionPageVo<ErpProductCategoryVo> categories(MasterOptionQuery query) {
        return page(query, categoryMapper.selectOptions(query));
    }

    public List<ErpProductVo> productsByIds(List<Long> ids) {
        return byIds(ids, productMapper::selectOptionsByIds, ErpProductVo::getId);
    }

    public List<ErpCustomerVo> customersByIds(List<Long> ids) {
        return byIds(ids, customerMapper::selectOptionsByIds, ErpCustomerVo::getId);
    }

    public List<ErpSupplierVo> suppliersByIds(List<Long> ids) {
        return byIds(ids, supplierMapper::selectOptionsByIds, ErpSupplierVo::getId);
    }

    public List<ErpWarehouseVo> warehousesByIds(List<Long> ids) {
        return byIds(ids, warehouseMapper::selectOptionsByIds, ErpWarehouseVo::getId);
    }

    public List<ErpProductCategoryVo> categoriesByIds(List<Long> ids) {
        return byIds(ids, categoryMapper::selectOptionsByIds, ErpProductCategoryVo::getId);
    }

    private <T> MasterOptionPageVo<T> page(MasterOptionQuery query, List<T> fetched) {
        boolean more = fetched.size() > query.getPageSize();
        List<T> rows = more ? new ArrayList<>(fetched.subList(0, query.getPageSize())) : fetched;
        return new MasterOptionPageVo<>(rows, more, more ? query.getPageNum() + 1L : null);
    }

    private <T> List<T> byIds(List<Long> ids, Function<List<Long>, List<T>> loader, Function<T, Long> getId) {
        if (ids == null || ids.isEmpty() || ids.size() > 200 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ServiceException("ids 必须包含 1–200 个正 ID", 400);
        }
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        Map<Long, T> loaded = new HashMap<>();
        loader.apply(unique).forEach(row -> loaded.put(getId.apply(row), row));
        return unique.stream().filter(loaded::containsKey).map(loaded::get).toList();
    }
}
