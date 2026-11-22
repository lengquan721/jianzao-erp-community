package org.dromara.common.log.aspect;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessStatus;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.log.enums.OperatorType;
import org.dromara.common.log.event.OperLogEvent;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 操作日志切面单元测试
 * <p>
 * 验证日志租户取 TenantHelper.getTenantId()（动态租户优先），而不是登录 token 里的原租户。
 * TenantHelper 依赖 MyBatis-Plus，故放在具备完整依赖的 admin 模块测试。
 *
 * @author Fawkes
 */
@Tag("dev")
class LogAspectTest {

    private ApplicationContext applicationContext;
    private MockedStatic<LoginHelper> loginHelper;
    private MockedStatic<TenantHelper> tenantHelper;
    private MockedStatic<ServletUtils> servletUtils;
    private MockedStatic<SpringUtils> springUtils;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        loginHelper = mockStatic(LoginHelper.class);
        tenantHelper = mockStatic(TenantHelper.class);
        servletUtils = mockStatic(ServletUtils.class);
        springUtils = mockStatic(SpringUtils.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/system/tenant/dynamic");
        when(request.getMethod()).thenReturn("POST");
        servletUtils.when(ServletUtils::getRequest).thenReturn(request);
        servletUtils.when(ServletUtils::getClientIP).thenReturn("127.0.0.1");
        springUtils.when(SpringUtils::context).thenReturn(applicationContext);

        LoginUser loginUser = new LoginUser();
        loginUser.setTenantId("000000");
        loginUser.setUsername("admin");
        loginUser.setDeptName("管理组");
        loginHelper.when(LoginHelper::getLoginUser).thenReturn(loginUser);
        // 登录 token 仍停在平台租户，操作发生在超管进入的目标企业
        loginHelper.when(LoginHelper::getTenantId).thenReturn("000000");
        tenantHelper.when(TenantHelper::getTenantId).thenReturn("100002");
    }

    @AfterEach
    void tearDown() {
        if (springUtils != null) {
            springUtils.close();
        }
        if (servletUtils != null) {
            servletUtils.close();
        }
        if (tenantHelper != null) {
            tenantHelper.close();
        }
        if (loginHelper != null) {
            loginHelper.close();
        }
    }

    @Test
    void requestAndResponseLoggingRedactsNestedSecretsAndTruncatesPayloads() throws Exception {
        LogAspect aspect = new LogAspect();
        var sanitizer = LogAspect.class.getDeclaredMethod("sanitizeJson", String.class);
        sanitizer.setAccessible(true);
        String sanitized = (String) sanitizer.invoke(aspect,
            "{\"name\":\"demo\",\"nested\":{\"password\":\"dont-log\",\"accessToken\":\"secret\"},\"payload\":\"" + "x".repeat(3000) + "\"}");
        assertFalse(sanitized.contains("dont-log"));
        assertFalse(sanitized.contains("secret"));
        assertTrue(sanitized.length() <= 2048 + "…[truncated]".length());
    }

    @Test
    void operLogUsesDynamicTenantInsteadOfLoginTenant() {
        JoinPoint joinPoint = mock(JoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn("enterTenant");
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(new Object());
        Log log = mock(Log.class);
        when(log.title()).thenReturn("租户管理");
        when(log.businessType()).thenReturn(BusinessType.OTHER);
        when(log.operatorType()).thenReturn(OperatorType.MANAGE);
        when(log.isSaveRequestData()).thenReturn(false);
        when(log.isSaveResponseData()).thenReturn(false);

        LogAspect aspect = new LogAspect();
        aspect.doBefore(joinPoint, log);
        aspect.doAfterReturning(joinPoint, log, R.ok());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(applicationContext).publishEvent(captor.capture());
        OperLogEvent operLog = (OperLogEvent) captor.getValue();
        assertEquals("100002", operLog.getTenantId());
        assertEquals("admin", operLog.getOperName());
        assertEquals(BusinessStatus.SUCCESS.ordinal(), operLog.getStatus());
        assertEquals("POST", operLog.getRequestMethod());
    }

}
