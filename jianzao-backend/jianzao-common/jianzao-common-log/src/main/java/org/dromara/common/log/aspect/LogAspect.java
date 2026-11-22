package org.dromara.common.log.aspect;

import cn.hutool.core.lang.Dict;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.StopWatch;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessStatus;
import org.dromara.common.log.event.OperLogEvent;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.http.HttpMethod;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 操作日志记录处理
 *
 * @author Lion Li
 */
@Slf4j
@Aspect
@AutoConfiguration
public class LogAspect {

    /**
     * 计时 key
     */
    private static final ThreadLocal<StopWatch> KEY_CACHE = new ThreadLocal<>();
    private static final int MAX_LOG_PAYLOAD_CHARS = 2048;
    private static final ObjectMapper LOG_SANITIZER_MAPPER = new ObjectMapper();
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
        "password", "oldpassword", "newpassword", "confirmpassword", "pwd", "token", "accesstoken",
        "refreshtoken", "authorization", "secret", "secretkey", "privatekey", "clientsecret",
        "phone", "mobile", "taxno", "idcard", "email", "encrypt-key"
    );

    /**
     * 处理请求前执行
     */
    @Before(value = "@annotation(controllerLog)")
    public void doBefore(JoinPoint joinPoint, Log controllerLog) {
        StopWatch stopWatch = new StopWatch();
        KEY_CACHE.set(stopWatch);
        stopWatch.start();
    }

    /**
     * 处理完请求后执行
     *
     * @param joinPoint 切点
     */
    @AfterReturning(pointcut = "@annotation(controllerLog)", returning = "jsonResult")
    public void doAfterReturning(JoinPoint joinPoint, Log controllerLog, Object jsonResult) {
        handleLog(joinPoint, controllerLog, null, jsonResult);
    }

    /**
     * 拦截异常操作
     *
     * @param joinPoint 切点
     * @param e         异常
     */
    @AfterThrowing(value = "@annotation(controllerLog)", throwing = "e")
    public void doAfterThrowing(JoinPoint joinPoint, Log controllerLog, Exception e) {
        handleLog(joinPoint, controllerLog, e, null);
    }

    protected void handleLog(final JoinPoint joinPoint, Log controllerLog, final Exception e, Object jsonResult) {
        try {

            // *========数据库日志=========*//
            OperLogEvent operLog = new OperLogEvent();
            // 动态租户优先：平台超管进入企业后的操作必须记在目标企业
            operLog.setTenantId(TenantHelper.getTenantId());
            operLog.setStatus(BusinessStatus.SUCCESS.ordinal());
            // 请求的地址
            String ip = ServletUtils.getClientIP();
            operLog.setOperIp(ip);
            operLog.setOperUrl(StringUtils.substring(ServletUtils.getRequest().getRequestURI(), 0, 255));
            LoginUser loginUser = LoginHelper.getLoginUser();
            operLog.setOperName(loginUser.getUsername());
            operLog.setDeptName(loginUser.getDeptName());

            if (e != null) {
                operLog.setStatus(BusinessStatus.FAIL.ordinal());
                operLog.setErrorMsg(StringUtils.substring(e.getMessage(), 0, 3800));
            }
            // 设置方法名称
            String className = joinPoint.getTarget().getClass().getName();
            String methodName = joinPoint.getSignature().getName();
            operLog.setMethod(className + "." + methodName + "()");
            // 设置请求方式
            operLog.setRequestMethod(ServletUtils.getRequest().getMethod());
            // 处理设置注解上的参数
            getControllerMethodDescription(joinPoint, controllerLog, operLog, jsonResult);
            // 设置消耗时间
            StopWatch stopWatch = KEY_CACHE.get();
            stopWatch.stop();
            operLog.setCostTime(stopWatch.getDuration().toMillis());
            // 发布事件保存数据库
            SpringUtils.context().publishEvent(operLog);
        } catch (Exception exp) {
            // 记录本地异常日志
            log.error("异常信息:{}", exp.getMessage());
        } finally {
            KEY_CACHE.remove();
        }
    }

    /**
     * 获取注解中对方法的描述信息 用于Controller层注解
     *
     * @param log     日志
     * @param operLog 操作日志
     * @throws Exception
     */
    public void getControllerMethodDescription(JoinPoint joinPoint, Log log, OperLogEvent operLog, Object jsonResult) throws Exception {
        // 设置action动作
        operLog.setBusinessType(log.businessType().ordinal());
        // 设置标题
        operLog.setTitle(log.title());
        // 设置操作人类别
        operLog.setOperatorType(log.operatorType().ordinal());
        // 是否需要保存request，参数和值
        if (log.isSaveRequestData()) {
            // 获取参数的信息，传入到数据库中。
            setRequestValue(joinPoint, operLog, log.excludeParamNames());
        }
        // 是否需要保存response，参数和值
        if (log.isSaveResponseData() && ObjectUtil.isNotNull(jsonResult)) {
            operLog.setJsonResult(StringUtils.substring(sanitizeJson(JsonUtils.toJsonString(jsonResult)), 0, MAX_LOG_PAYLOAD_CHARS));
        }
    }

    /**
     * 获取请求的参数，放到log中
     *
     * @param operLog 操作日志
     * @throws Exception 异常
     */
    private void setRequestValue(JoinPoint joinPoint, OperLogEvent operLog, String[] excludeParamNames) throws Exception {
        Map<String, String> paramsMap = ServletUtils.getParamMap(ServletUtils.getRequest());
        String requestMethod = operLog.getRequestMethod();
        if (MapUtil.isEmpty(paramsMap) && StringUtils.equalsAny(requestMethod, HttpMethod.PUT.name(), HttpMethod.POST.name(), HttpMethod.DELETE.name())) {
            String params = argsArrayToString(joinPoint.getArgs(), excludeParamNames);
            operLog.setOperParam(StringUtils.substring(params, 0, MAX_LOG_PAYLOAD_CHARS));
        } else {
            String[] excluded = ArrayUtil.addAll(SystemConstants.EXCLUDE_PROPERTIES, excludeParamNames);
            paramsMap.keySet().removeIf(name -> isExcludedField(name, excluded));
            operLog.setOperParam(StringUtils.substring(sanitizeJson(JsonUtils.toJsonString(paramsMap)), 0, MAX_LOG_PAYLOAD_CHARS));
        }
    }

    /**
     * 参数拼装
     */
    private String argsArrayToString(Object[] paramsArray, String[] excludeParamNames) {
        StringJoiner params = new StringJoiner(" ");
        if (ArrayUtil.isEmpty(paramsArray)) {
            return params.toString();
        }
        String[] exclude = ArrayUtil.addAll(excludeParamNames, SystemConstants.EXCLUDE_PROPERTIES);
        for (Object o : paramsArray) {
            if (ObjectUtil.isNotNull(o) && !isFilterObject(o)) {
                String str = "";
                if (o instanceof List<?> list) {
                    List<Dict> list1 = new ArrayList<>();
                    for (Object obj : list) {
                        String str1 = JsonUtils.toJsonString(obj);
                        Dict dict = JsonUtils.parseMap(str1);
                        if (MapUtil.isNotEmpty(dict)) {
                            redactMap(dict, exclude);
                            list1.add(dict);
                        }
                    }
                    str = JsonUtils.toJsonString(list1);
                } else {
                    str = JsonUtils.toJsonString(o);
                    Dict dict = JsonUtils.parseMap(str);
                    if (MapUtil.isNotEmpty(dict)) {
                        redactMap(dict, exclude);
                        str = sanitizeJson(JsonUtils.toJsonString(dict));
                    }
                }
                params.add(sanitizeJson(str));
            }
        }
        return params.toString();
    }

    private String sanitizeJson(String json) {
        if (json == null || json.length() > 100_000) {
            return "[log omitted: payload too large]";
        }
        try {
            JsonNode root = LOG_SANITIZER_MAPPER.readTree(json);
            redactJson(root);
            String sanitized = root == null ? "" : root.toString();
            if (sanitized.length() <= MAX_LOG_PAYLOAD_CHARS) {
                return sanitized;
            }
            String marker = "…[truncated]";
            return sanitized.substring(0, MAX_LOG_PAYLOAD_CHARS - marker.length()) + marker;
        } catch (Exception ignored) {
            return "[log omitted: invalid or non-JSON payload]";
        }
    }

    private void redactJson(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            List<String> remove = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(name -> {
                if (isExcludedField(name, SystemConstants.EXCLUDE_PROPERTIES)) remove.add(name);
            });
            remove.forEach(objectNode::remove);
            objectNode.elements().forEachRemaining(this::redactJson);
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(this::redactJson);
        }
    }

    private void redactMap(Map<?, ?> map, String[] excluded) {
        List<Object> remove = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (key instanceof String name && isExcludedField(name, excluded)) {
                remove.add(key);
            }
        }
        remove.forEach(map::remove);
        for (Object value : map.values()) {
            if (value instanceof Map<?, ?> nested) {
                redactMap(nested, excluded);
            } else if (value instanceof Collection<?> collection) {
                for (Object child : collection) {
                    if (child instanceof Map<?, ?> nested) redactMap(nested, excluded);
                }
            }
        }
    }

    private boolean isExcludedField(String field, String[] excluded) {
        if (field == null) return false;
        String normalized = field.toLowerCase(Locale.ROOT);
        if (SENSITIVE_FIELDS.contains(normalized)) return true;
        for (String candidate : excluded) {
            if (candidate != null && candidate.equalsIgnoreCase(field)) return true;
        }
        return false;
    }

    /**
     * 判断是否需要过滤的对象。
     *
     * @param o 对象信息。
     * @return 如果是需要过滤的对象，则返回true；否则返回false。
     */
    @SuppressWarnings("rawtypes")
    public boolean isFilterObject(final Object o) {
        Class<?> clazz = o.getClass();
        if (clazz.isArray()) {
            return MultipartFile.class.isAssignableFrom(clazz.getComponentType());
        } else if (Collection.class.isAssignableFrom(clazz)) {
            Collection collection = (Collection) o;
            for (Object value : collection) {
                return value instanceof MultipartFile;
            }
        } else if (Map.class.isAssignableFrom(clazz)) {
            Map map = (Map) o;
            for (Object value : map.values()) {
                return value instanceof MultipartFile;
            }
        }
        return o instanceof MultipartFile || o instanceof HttpServletRequest || o instanceof HttpServletResponse
               || o instanceof BindingResult;
    }
}
