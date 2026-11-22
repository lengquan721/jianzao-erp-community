package org.erp.fin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.erp.fin.domain.FinAccount;
import org.erp.fin.mapper.*;
import org.erp.fin.service.impl.FinAccountServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** OR 只扩下拉的注解合同及最小投影，不将反射校验冒充 HTTP 鉴权集成测试。 */
@Tag("dev")
class FinAccountOptionsBoundaryTest {
    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "options-boundary"), FinAccount.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"erp:finance:receipt:list", "erp:finance:payment:list", "erp:finance:other-flow:list", "erp:finance:account:list"})
    void eachDropdownRoleStillCannotReadAccountDetailsOrFlowsWithoutQueryPermission(String role) throws Exception {
        SaCheckPermission options = FinAccountController.class.getMethod("options").getAnnotation(SaCheckPermission.class);
        assertEquals(SaMode.OR, options.mode());
        assertTrue(Arrays.asList(options.value()).contains(role));
        for (var method : List.of(FinAccountController.class.getMethod("getInfo", Long.class),
            FinAccountController.class.getMethod("flows", Long.class, PageQuery.class))) {
            var required = method.getAnnotation(SaCheckPermission.class);
            assertArrayEquals(new String[]{"erp:finance:account:query"}, required.value());
            assertFalse(Arrays.asList(required.value()).contains(role));
        }
        var list = FinAccountController.class.getMethod("list", String.class, String.class, Integer.class, PageQuery.class)
            .getAnnotation(SaCheckPermission.class);
        assertArrayEquals(new String[]{"erp:finance:account:list"}, list.value());
        if (!role.equals("erp:finance:account:list")) { assertFalse(Arrays.asList(list.value()).contains(role)); }
    }

    @Test void optionsEvenWithOverbroadMockEntityCannotSerializeSensitiveValues() {
        var accounts = mock(FinAccountMapper.class);
        var receipts = mock(FinReceiptMapper.class);
        var payments = mock(FinPaymentMapper.class);
        var flows = mock(FinOtherFlowMapper.class);
        var service = new FinAccountServiceImpl(accounts, receipts, payments, flows);
        var row = new FinAccount(); row.setId(7L); row.setName("结算账户"); row.setCode("A7"); row.setType("BANK");
        row.setStatus(1); row.setOpeningBalance(new BigDecimal("123456.78")); row.setRemark("private");
        row.setCreateTime(new java.util.Date());
        when(accounts.selectList(any(Wrapper.class))).thenReturn(List.of(row));
        var result = service.options();
        assertEquals(7L, result.get(0).getId()); assertEquals("结算账户", result.get(0).getName());
        assertNull(result.get(0).getOpeningBalance()); assertNull(result.get(0).getBalance());
        assertNull(result.get(0).getRemark()); assertNull(result.get(0).getCreateTime());
        ArgumentCaptor<Wrapper<FinAccount>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(accounts).selectList(query.capture());
        assertEquals("id,code,name,type,status", query.getValue().getSqlSelect());
        verifyNoInteractions(receipts, payments, flows);
        verifyNoMoreInteractions(accounts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"erp:finance:receipt:query", "erp:finance:payment:query", "erp:finance:other-flow:query", "erp:finance:list"})
    void unrelatedReadRoleDoesNotAcquireDropdownAccess(String role) throws Exception {
        var permission = FinAccountController.class.getMethod("options").getAnnotation(SaCheckPermission.class);
        assertFalse(Arrays.asList(permission.value()).contains(role));
    }
}
