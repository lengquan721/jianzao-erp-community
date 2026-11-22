package org.dromara.web.listener;

import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import io.github.linpeilie.Converter;
import jakarta.servlet.http.HttpServletRequest;
import net.sf.jsqlparser.expression.StringValue;
import org.dromara.common.core.constant.Constants;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.ip.AddressUtils;
import org.dromara.common.log.event.LogininforEvent;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.handle.PlusTenantLineHandler;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.system.domain.SysLogininfor;
import org.dromara.system.domain.bo.SysLogininforBo;
import org.dromara.system.mapper.SysLogininforMapper;
import org.dromara.system.service.ISysClientService;
import org.dromara.system.service.impl.SysLogininforServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class SysLogininforServiceImplTenantContextTest {

    private static final String TENANT_ID = "200001";
    private static final String OUTER_TENANT_ID = "200002";
    private static final String CLIENT_IP = "192.0.2.26";
    private static Converter converter;

    private SysLogininforMapper mapper;
    private SysLogininforServiceImpl service;
    private HttpServletRequest request;
    private MockedStatic<SpringUtil> springUtil;
    private MockedStatic<LoginHelper> loginHelper;
    private MockedStatic<ServletUtils> servletUtils;
    private MockedStatic<AddressUtils> addressUtils;

    @BeforeAll
    static void initializeMapstructConverter() throws ReflectiveOperationException {
        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class)) {
            spring.when(() -> SpringUtil.getBean(Converter.class)).thenReturn(mock(Converter.class));
            MapstructUtils.convert((Object) null, SysLogininfor.class);
        }
        Field field = MapstructUtils.class.getDeclaredField("CONVERTER");
        field.setAccessible(true);
        converter = (Converter) field.get(null);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(SysLogininforMapper.class);
        service = new SysLogininforServiceImpl(mapper, mock(ISysClientService.class));
        request = mock(HttpServletRequest.class);

        springUtil = mockStatic(SpringUtil.class);
        springUtil.when(() -> SpringUtil.getProperty("tenant.enable")).thenReturn("true");
        loginHelper = mockStatic(LoginHelper.class);
        loginHelper.when(LoginHelper::isLogin).thenReturn(false);
        servletUtils = mockStatic(ServletUtils.class);
        servletUtils.when(() -> ServletUtils.getClientIP(request)).thenReturn(CLIENT_IP);
        addressUtils = mockStatic(AddressUtils.class);
        addressUtils.when(() -> AddressUtils.getRealAddressByIP(CLIENT_IP)).thenReturn("test-address");
        when(request.getHeader("User-Agent")).thenReturn(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
        when(converter.convert(any(SysLogininforBo.class), eq(SysLogininfor.class))).thenAnswer(invocation -> {
            SysLogininforBo source = invocation.getArgument(0);
            SysLogininfor entity = new SysLogininfor();
            entity.setTenantId(source.getTenantId());
            return entity;
        });
    }

    @AfterEach
    void tearDown() {
        addressUtils.close();
        servletUtils.close();
        loginHelper.close();
        springUtil.close();
    }

    @Test
    void asynchronousEventRestoresItsTenantForBusinessInsertWithoutRequestThreadLocal() {
        assertNull(TenantHelper.getTenantId(), "the async event thread must not inherit a request tenant");
        doAnswer(invocation -> {
            assertTenantLineUses(TENANT_ID);
            return 1;
        }).when(mapper).insert(any(SysLogininfor.class));

        service.recordLogininfor(event());

        ArgumentCaptor<SysLogininfor> inserted = ArgumentCaptor.forClass(SysLogininfor.class);
        verify(mapper).insert(inserted.capture());
        assertEquals(TENANT_ID, inserted.getValue().getTenantId());
        assertNull(TenantHelper.getTenantId(), "event tenant must be removed after async insert");
    }

    @Test
    void asynchronousInsertFailureRestoresPreviousDynamicTenant() {
        IllegalStateException expected = new IllegalStateException("synthetic mapper failure");
        doAnswer(invocation -> {
            assertTenantLineUses(TENANT_ID);
            throw expected;
        }).when(mapper).insert(any(SysLogininfor.class));

        TenantHelper.dynamic(OUTER_TENANT_ID, () -> {
            IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> service.recordLogininfor(event()));
            assertSame(expected, actual);
            assertEquals(OUTER_TENANT_ID, TenantHelper.getTenantId(),
                "event failure must restore an outer dynamic tenant");
        });
        assertNull(TenantHelper.getTenantId(), "outer tenant must be removed after its scope");
    }

    private LogininforEvent event() {
        LogininforEvent event = new LogininforEvent();
        event.setTenantId(TENANT_ID);
        event.setUsername("test-user");
        event.setStatus(Constants.LOGIN_SUCCESS);
        event.setMessage("test-message");
        event.setRequest(request);
        return event;
    }

    private void assertTenantLineUses(String tenantId) {
        assertEquals(tenantId, TenantHelper.getTenantId());
        PlusTenantLineHandler handler = new PlusTenantLineHandler();
        assertFalse(handler.ignoreTable("sys_logininfor"), "login-info table remains tenant-filtered");
        assertEquals(tenantId, ((StringValue) handler.getTenantId()).getValue());
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine(""),
            "tenant-line interception must not be globally ignored");
    }
}
