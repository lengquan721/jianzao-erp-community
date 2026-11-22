package org.erp.fin.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.erp.fin.domain.FinAccount;
import org.erp.fin.domain.FinOtherFlow;
import org.erp.fin.domain.FinPayment;
import org.erp.fin.domain.FinReceipt;
import org.erp.fin.mapper.FinAccountMapper;
import org.erp.fin.mapper.FinOtherFlowMapper;
import org.erp.fin.mapper.FinPaymentMapper;
import org.erp.fin.mapper.FinReceiptMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class FinAccountRefundBalanceTest {

    @Mock private FinAccountMapper accountMapper;
    @Mock private FinReceiptMapper receiptMapper;
    @Mock private FinPaymentMapper paymentMapper;
    @Mock private FinOtherFlowMapper otherFlowMapper;

    private FinAccountServiceImpl service;

    @BeforeEach
    void setUp() {
        var configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "refund-balance-test"), FinReceipt.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "refund-balance-test"), FinPayment.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "refund-balance-test"), FinOtherFlow.class);
        service = new FinAccountServiceImpl(accountMapper, receiptMapper, paymentMapper, otherFlowMapper);
    }

    @Test
    void balanceSubtractsLinkedSaleReturnRefundInsteadOfCountingItAsReceipt() {
        FinAccount account = new FinAccount();
        account.setId(7L);
        account.setOpeningBalance(new BigDecimal("100.00"));
        when(accountMapper.selectById(7L)).thenReturn(account);

        FinReceipt ordinary = receipt("500.00", 0);
        FinReceipt refund = receipt("40.00", 1);
        when(receiptMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ordinary, refund));
        when(paymentMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(otherFlowMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        var result = service.selectById(7L);

        assertEquals(0, new BigDecimal("560.00").compareTo(result.getBalance()));
    }

    private FinReceipt receipt(String amount, int negative) {
        FinReceipt receipt = new FinReceipt();
        receipt.setAmount(new BigDecimal(amount));
        receipt.setNegative(negative);
        return receipt;
    }
}
