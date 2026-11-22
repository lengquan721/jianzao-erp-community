package org.erp.master.service;

import org.dromara.common.core.exception.ServiceException;
import org.erp.master.domain.bo.MasterOptionQuery;
import org.erp.master.domain.vo.ErpCustomerVo;
import org.erp.master.domain.vo.ErpProductCategoryVo;
import org.erp.master.domain.vo.ErpProductVo;
import org.erp.master.domain.vo.ErpSupplierVo;
import org.erp.master.domain.vo.ErpWarehouseVo;
import org.erp.master.mapper.ErpCustomerMapper;
import org.erp.master.mapper.ErpProductCategoryMapper;
import org.erp.master.mapper.ErpProductMapper;
import org.erp.master.mapper.ErpSupplierMapper;
import org.erp.master.mapper.ErpWarehouseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class MasterOptionServiceTest {
    @Mock ErpProductMapper products;
    @Mock ErpCustomerMapper customers;
    @Mock ErpSupplierMapper suppliers;
    @Mock ErpWarehouseMapper warehouses;
    @Mock ErpProductCategoryMapper categories;
    MasterOptionService service;

    @BeforeEach
    void setUp() {
        service = new MasterOptionService(products, customers, suppliers, warehouses, categories);
    }

    @Test
    void query_validationAndEscaping_preservesLiteralPrefixAndBoundedNameSearch() {
        var q = MasterOptionQuery.of("  A%!_  ", null, null, 3, 100);
        assertEquals("A%!_", q.getKeyword());
        assertEquals("A!%!!!_%", q.getPrefix());
        assertEquals("%A!%!!!_%", q.getContains());
        assertEquals(200, q.getOffset());
        assertEquals(101, q.getFetchSize());
        assertNull(MasterOptionQuery.of("钢", null, null, 1, 30).getContains());
        assertEquals("钢%", MasterOptionQuery.of("钢", null, null, 1, 30).getPrefix());
        assertNull(MasterOptionQuery.of(" \t", null, null, 1, 30).getKeyword());
        assertEquals("P01", MasterOptionQuery.of(null, " P01 ", 0, 1, 30).getCode());
        assertTrue(MasterOptionQuery.of(null, null, null, Integer.MAX_VALUE, 100).getOffset() > Integer.MAX_VALUE);
    }

    @Test
    void query_invalidInput_neverReachesMapper() {
        assertThrows(ServiceException.class, () -> MasterOptionQuery.of(null, null, null, 0, 30));
        assertThrows(ServiceException.class, () -> MasterOptionQuery.of(null, null, null, 1, 0));
        assertThrows(ServiceException.class, () -> MasterOptionQuery.of(null, null, null, 1, 101));
        assertThrows(ServiceException.class, () -> MasterOptionQuery.of(null, null, 2, 1, 30));
        assertThrows(ServiceException.class, () -> MasterOptionQuery.of("a", "a", null, 1, 30));
        assertThrows(ServiceException.class, () -> MasterOptionQuery.of("a".repeat(101), null, null, 1, 30));
        assertThrows(ServiceException.class, () -> MasterOptionQuery.of(null, "a".repeat(33), null, 1, 30));
        verifyNoInteractions(products, customers, suppliers, warehouses, categories);
    }

    @Test
    void productPages_haveSentinelAndReachBeyondTwoHundred_withoutDroppingInactiveRows() {
        var q = MasterOptionQuery.of(null, null, null, 3, 100);
        var inactive = product(250L);
        inactive.setStatus(0);
        when(products.selectOptions(q, "MATERIAL", 4L)).thenReturn(Collections.nCopies(101, inactive));
        var page = service.products(q, "MATERIAL", 4L);
        assertEquals(100, page.rows().size());
        assertTrue(page.hasMore());
        assertEquals(4L, page.nextPageNum());
        assertEquals(0, page.rows().get(0).getStatus());
        verify(products).selectOptions(q, "MATERIAL", 4L);
        verify(products, never()).selectVoListDetail(any(), any(), any(), any(), any());
    }

    @Test
    void lastAndOutOfRangePages_doNotRepeatFirstPage() {
        var q = MasterOptionQuery.of("P", null, null, 1000, 30);
        when(customers.selectOptions(q)).thenReturn(List.of());
        var page = service.customers(q);
        assertFalse(page.hasMore());
        assertNull(page.nextPageNum());
        assertTrue(page.rows().isEmpty());
        when(suppliers.selectOptions(q)).thenReturn(List.of(new ErpSupplierVo()));
        assertEquals(1, service.suppliers(q).rows().size());
        assertFalse(service.suppliers(q).hasMore());
        assertNull(service.suppliers(q).nextPageNum());
    }

    @Test
    void allOptionTypes_useIndependentBoundedQueries() {
        var q = MasterOptionQuery.of(null, null, 1, 1, 1);
        when(customers.selectOptions(q)).thenReturn(List.of(new ErpCustomerVo(), new ErpCustomerVo()));
        when(suppliers.selectOptions(q)).thenReturn(List.of(new ErpSupplierVo(), new ErpSupplierVo()));
        when(warehouses.selectOptions(q)).thenReturn(List.of(new ErpWarehouseVo(), new ErpWarehouseVo()));
        when(categories.selectOptions(q)).thenReturn(List.of(new ErpProductCategoryVo(), new ErpProductCategoryVo()));
        assertTrue(service.customers(q).hasMore());
        assertTrue(service.suppliers(q).hasMore());
        assertTrue(service.warehouses(q).hasMore());
        assertTrue(service.categories(q).hasMore());
    }

    @Test
    void selectedIds_deduplicatePreserveRequestOrderAndDoNotDependOnFirstPage() {
        var inactive = product(950L);
        inactive.setStatus(0);
        when(products.selectOptionsByIds(List.of(950L, 1L, 999L)))
            .thenReturn(List.of(product(1L), inactive));
        var rows = service.productsByIds(List.of(950L, 1L, 950L, 999L));
        assertEquals(List.of(950L, 1L), rows.stream().map(ErpProductVo::getId).toList());
        assertEquals(0, rows.get(0).getStatus());
        verify(products).selectOptionsByIds(List.of(950L, 1L, 999L));
        verify(products, never()).selectOptions(any(), any(), any());
    }

    @Test
    void idsValidation_countsRawInputs_neverSilentlyTruncatesOrBuildsEmptyIn() {
        assertThrows(ServiceException.class, () -> service.productsByIds(null));
        assertThrows(ServiceException.class, () -> service.customersByIds(List.of()));
        assertThrows(ServiceException.class, () -> service.suppliersByIds(Collections.nCopies(201, 1L)));
        assertThrows(ServiceException.class, () -> service.warehousesByIds(List.of(-1L)));
        assertThrows(ServiceException.class, () -> service.categoriesByIds(Arrays.asList(1L, null)));
        assertThrows(ServiceException.class, () -> service.productsByIds(List.of(0L)));
        verifyNoInteractions(products, customers, suppliers, warehouses, categories);
    }

    @Test
    void byIds_allFiveTypes_supportSelectedItems() {
        when(products.selectOptionsByIds(List.of(1L))).thenReturn(List.of(product(1L)));
        var customer = new ErpCustomerVo(); customer.setId(1L);
        var supplier = new ErpSupplierVo(); supplier.setId(1L);
        var warehouse = new ErpWarehouseVo(); warehouse.setId(1L);
        var category = new ErpProductCategoryVo(); category.setId(1L);
        when(customers.selectOptionsByIds(List.of(1L))).thenReturn(List.of(customer));
        when(suppliers.selectOptionsByIds(List.of(1L))).thenReturn(List.of(supplier));
        when(warehouses.selectOptionsByIds(List.of(1L))).thenReturn(List.of(warehouse));
        when(categories.selectOptionsByIds(List.of(1L))).thenReturn(List.of(category));
        assertEquals(1, service.productsByIds(List.of(1L)).size());
        assertEquals(List.of(customer), service.customersByIds(List.of(1L)));
        assertEquals(List.of(supplier), service.suppliersByIds(List.of(1L)));
        assertEquals(List.of(warehouse), service.warehousesByIds(List.of(1L)));
        assertEquals(List.of(category), service.categoriesByIds(List.of(1L)));
    }

    @Test
    void invalidProductType_isNotAnUnfilteredQuery() {
        assertThrows(ServiceException.class, () -> service.products(MasterOptionQuery.of(null, null, null, 1, 30), "ALL", null));
        verifyNoInteractions(products);
    }

    private ErpProductVo product(Long id) {
        var row = new ErpProductVo();
        row.setId(id);
        row.setStatus(1);
        row.setQtyScale(3);
        row.setCode("P" + id);
        return row;
    }
}
