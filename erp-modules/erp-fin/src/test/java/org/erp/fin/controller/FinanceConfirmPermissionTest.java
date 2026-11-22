package org.erp.fin.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.domain.R;
import org.erp.fin.domain.bo.FinOtherFlowBo;
import org.erp.fin.domain.bo.FinPaymentBo;
import org.erp.fin.domain.bo.FinReceiptBo;
import org.erp.fin.service.IFinOtherFlowService;
import org.erp.fin.service.IFinPaymentService;
import org.erp.fin.service.IFinReceiptService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("dev")
class FinanceConfirmPermissionTest {

    private static final long BILL_ID = 42L;

    @ParameterizedTest(name = "{0} retains the ordinary write permission")
    @MethodSource("endpoints")
    void retainsWritePermission(Endpoint endpoint) throws NoSuchMethodException {
        var method = endpoint.edit()
            ? endpoint.controller().getMethod("edit", Long.class, endpoint.body())
            : endpoint.controller().getMethod("add", endpoint.body());
        assertArrayEquals(new String[]{endpoint.permission() + (endpoint.edit() ? ":edit" : ":add")},
            method.getAnnotation(SaCheckPermission.class).value());
    }

    @ParameterizedTest(name = "{0} draft writes do not require confirm permission")
    @MethodSource("endpoints")
    void draftDoesNotRequireConfirm(Endpoint endpoint) {
        Invocation invocation = endpoint.invocation().get();
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            invocation.confirm().accept(false);
            assertEquals(R.SUCCESS, invocation.call().get().getCode());
            stp.verifyNoInteractions();
            invocation.verifyWrite().run();
        }
    }

    @ParameterizedTest(name = "{0} rejects confirmation before calling the write service")
    @MethodSource("endpoints")
    void deniedConfirmationDoesNotWrite(Endpoint endpoint) {
        Invocation invocation = endpoint.invocation().get();
        String permission = endpoint.permission() + ":confirm";
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkPermission(permission)).thenThrow(new NotPermissionException(permission));
            invocation.confirm().accept(true);
            assertThrows(NotPermissionException.class, invocation.call()::get);
            verifyNoInteractions(invocation.service());
        }
    }

    @ParameterizedTest(name = "{0} authorized confirmation reaches the write service")
    @MethodSource("endpoints")
    void authorizedConfirmationWrites(Endpoint endpoint) {
        Invocation invocation = endpoint.invocation().get();
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            invocation.confirm().accept(true);
            assertEquals(R.SUCCESS, invocation.call().get().getCode());
            stp.verify(() -> StpUtil.checkPermission(endpoint.permission() + ":confirm"));
            invocation.verifyWrite().run();
        }
    }

    private static Stream<Endpoint> endpoints() {
        return Stream.of(false, true).flatMap(edit -> Stream.of(
            new Endpoint("erp:finance:receipt", edit, FinReceiptController.class, FinReceiptBo.class, () -> receipt(edit)),
            new Endpoint("erp:finance:payment", edit, FinPaymentController.class, FinPaymentBo.class, () -> payment(edit)),
            new Endpoint("erp:finance:other-flow", edit, FinOtherFlowController.class, FinOtherFlowBo.class, () -> otherFlow(edit))
        ));
    }

    private static Invocation receipt(boolean edit) {
        IFinReceiptService service = mock(IFinReceiptService.class);
        FinReceiptController controller = new FinReceiptController(service);
        FinReceiptBo bo = new FinReceiptBo();
        return new Invocation(service, bo::setConfirm,
            () -> edit ? controller.edit(BILL_ID, bo) : controller.add(bo),
            () -> { if (edit) verify(service).update(BILL_ID, bo); else verify(service).insert(bo); });
    }

    private static Invocation payment(boolean edit) {
        IFinPaymentService service = mock(IFinPaymentService.class);
        FinPaymentController controller = new FinPaymentController(service);
        FinPaymentBo bo = new FinPaymentBo();
        return new Invocation(service, bo::setConfirm,
            () -> edit ? controller.edit(BILL_ID, bo) : controller.add(bo),
            () -> { if (edit) verify(service).update(BILL_ID, bo); else verify(service).insert(bo); });
    }

    private static Invocation otherFlow(boolean edit) {
        IFinOtherFlowService service = mock(IFinOtherFlowService.class);
        FinOtherFlowController controller = new FinOtherFlowController(service);
        FinOtherFlowBo bo = new FinOtherFlowBo();
        return new Invocation(service, bo::setConfirm,
            () -> edit ? controller.edit(BILL_ID, bo) : controller.add(bo),
            () -> { if (edit) verify(service).update(BILL_ID, bo); else verify(service).insert(bo); });
    }

    private record Invocation(Object service, Consumer<Boolean> confirm, Supplier<R<?>> call, Runnable verifyWrite) {
    }

    private record Endpoint(String permission, boolean edit, Class<?> controller, Class<?> body, Supplier<Invocation> invocation) {

        @Override
        public String toString() {
            return permission + (edit ? ":edit" : ":add");
        }
    }
}
