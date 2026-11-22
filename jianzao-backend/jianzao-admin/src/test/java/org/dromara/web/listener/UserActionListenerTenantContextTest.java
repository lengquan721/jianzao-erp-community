package org.dromara.web.listener;

import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import jakarta.servlet.http.HttpServletRequest;
import net.sf.jsqlparser.expression.StringValue;
import org.dromara.common.core.utils.MessageUtils;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.ip.AddressUtils;
import org.dromara.common.log.event.LogininforEvent;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.handle.PlusTenantLineHandler;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.web.service.SysLoginService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class UserActionListenerTenantContextTest {

    private static final String TENANT_ID = "100001";
    private static final String OUTER_TENANT_ID = "100002";
    private static final String CLIENT_IP = "192.0.2.25";
    private static final Long USER_ID = 42L;

    private SysLoginService loginService;
    private UserActionListener listener;
    private HttpServletRequest request;
    private ApplicationContext applicationContext;
    private MockedStatic<SpringUtil> springUtil;
    private MockedStatic<SpringUtils> springUtils;
    private MockedStatic<LoginHelper> loginHelper;
    private MockedStatic<ServletUtils> servletUtils;
    private MockedStatic<AddressUtils> addressUtils;
    private MockedStatic<RedisUtils> redisUtils;
    private MockedStatic<MessageUtils> messageUtils;

    @BeforeEach
    void setUp() {
        loginService = mock(SysLoginService.class);
        listener = new UserActionListener(loginService);
        request = mock(HttpServletRequest.class);
        applicationContext = mock(ApplicationContext.class);

        // TenantHelper 保持真实实现，以验证 dynamic 的实际 ThreadLocal 传递和恢复。
        springUtil = mockStatic(SpringUtil.class);
        springUtil.when(() -> SpringUtil.getProperty("tenant.enable")).thenReturn("true");
        springUtil.when(() -> SpringUtil.getBean(MessageSource.class)).thenReturn(mock(MessageSource.class));
        springUtils = mockStatic(SpringUtils.class);
        springUtils.when(SpringUtils::context).thenReturn(applicationContext);
        loginHelper = mockStatic(LoginHelper.class);
        loginHelper.when(LoginHelper::isLogin).thenReturn(false);
        servletUtils = mockStatic(ServletUtils.class);
        servletUtils.when(ServletUtils::getRequest).thenReturn(request);
        servletUtils.when(ServletUtils::getClientIP).thenReturn(CLIENT_IP);
        addressUtils = mockStatic(AddressUtils.class);
        addressUtils.when(() -> AddressUtils.getRealAddressByIP(CLIENT_IP)).thenReturn("测试地址");
        redisUtils = mockStatic(RedisUtils.class);
        messageUtils = mockStatic(MessageUtils.class);
        messageUtils.when(() -> MessageUtils.message("user.login.success")).thenReturn("登录成功");

        when(request.getHeader("User-Agent")).thenReturn(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
    }

    @AfterEach
    void tearDown() {
        messageUtils.close();
        redisUtils.close();
        addressUtils.close();
        servletUtils.close();
        loginHelper.close();
        springUtils.close();
        springUtil.close();
    }

    @Test
    void recordLoginInfoReceivesExplicitTenantAndSuccessRestoresPreviousContext() {
        doAnswer(invocation -> {
            assertTenantLineUses(TENANT_ID);
            return null;
        }).when(loginService).recordLoginInfo(USER_ID, CLIENT_IP);

        TenantHelper.dynamic(OUTER_TENANT_ID, () -> {
            listener.doLogin("login", USER_ID, "token-value", loginParameter());
            assertEquals(OUTER_TENANT_ID, TenantHelper.getTenantId(), "successful login must restore the caller's tenant");
        });

        assertNull(TenantHelper.getTenantId(), "outer dynamic tenant must also be removed after returning");
        verify(loginService).recordLoginInfo(USER_ID, CLIENT_IP);
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationContext).publishEvent(eventCaptor.capture());
        assertEquals(TENANT_ID, ((LogininforEvent) eventCaptor.getValue()).getTenantId());
    }

    @Test
    void recordLoginInfoExceptionRestoresPreviousContext() {
        RuntimeException expected = new IllegalStateException("simulated user update failure");
        doAnswer(invocation -> {
            assertTenantLineUses(TENANT_ID);
            throw expected;
        }).when(loginService).recordLoginInfo(USER_ID, CLIENT_IP);

        TenantHelper.dynamic(OUTER_TENANT_ID, () -> {
            RuntimeException actual = assertThrows(RuntimeException.class,
                () -> listener.doLogin("login", USER_ID, "token-value", loginParameter()));
            assertSame(expected, actual);
            assertEquals(OUTER_TENANT_ID, TenantHelper.getTenantId(), "exception must not leak or erase the caller's tenant");
        });

        assertNull(TenantHelper.getTenantId(), "outer dynamic tenant must be removed after returning");
    }

    private SaLoginParameter loginParameter() {
        SaLoginParameter parameter = mock(SaLoginParameter.class);
        when(parameter.getExtra(LoginHelper.TENANT_KEY)).thenReturn(TENANT_ID);
        when(parameter.getExtra(LoginHelper.USER_KEY)).thenReturn(USER_ID);
        when(parameter.getExtra(LoginHelper.USER_NAME_KEY)).thenReturn("test-user");
        when(parameter.getExtra(LoginHelper.CLIENT_KEY)).thenReturn("web");
        when(parameter.getExtra(LoginHelper.DEPT_NAME_KEY)).thenReturn("test-dept");
        when(parameter.getDeviceType()).thenReturn("pc");
        when(parameter.getTimeout()).thenReturn(-1L);
        return parameter;
    }

    private void assertTenantLineUses(String tenantId) {
        assertEquals(tenantId, TenantHelper.getTenantId());
        PlusTenantLineHandler tenantLineHandler = new PlusTenantLineHandler();
        assertFalse(tenantLineHandler.ignoreTable("sys_user"), "business tables must remain tenant-filtered");
        assertEquals(tenantId, ((StringValue) tenantLineHandler.getTenantId()).getValue());
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine(""), "tenant-line interception must not be globally ignored");
    }
}
