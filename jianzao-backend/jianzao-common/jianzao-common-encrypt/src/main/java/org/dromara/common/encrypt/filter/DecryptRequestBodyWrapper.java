package org.dromara.common.encrypt.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.dromara.common.core.constant.Constants;
import org.dromara.common.encrypt.utils.EncryptUtils;
import org.dromara.common.core.utils.RequestBodyLimits;
import org.springframework.http.MediaType;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 解密请求参数工具类
 *
 * @author wdhcr
 */
public class DecryptRequestBodyWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    public DecryptRequestBodyWrapper(HttpServletRequest request, String privateKey, String headerFlag) throws IOException {
        super(request);
        // 获取 AES 密码 采用 RSA 加密
        String headerRsa = request.getHeader(headerFlag);
        String decryptAes = EncryptUtils.decryptByRsa(headerRsa, privateKey);
        // 解密 AES 密码
        String aesPassword = EncryptUtils.decryptByBase64(decryptAes);
        request.setCharacterEncoding(Constants.UTF8);
        byte[] readBytes = RequestBodyLimits.readLimited(request.getInputStream(),
            RequestBodyLimits.encryptedWireLimit(request));
        String requestBody = new String(readBytes, StandardCharsets.UTF_8);
        // 解密 body 采用 AES 加密。明文再次单独校验，避免密文膨胀绕过明文预算。
        String decryptBody = EncryptUtils.decryptByAes(requestBody, aesPassword);
        body = decryptBody.getBytes(StandardCharsets.UTF_8);
        if (body.length > RequestBodyLimits.plainLimit(request)) {
            throw new RequestBodyLimits.BodyTooLargeException();
        }
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream()));
    }


    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public String getContentType() {
        return MediaType.APPLICATION_JSON_VALUE;
    }


    @Override
    public ServletInputStream getInputStream() {
        final ByteArrayInputStream bais = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return bais.read();
            }

            @Override
            public int available() {
                return bais.available();
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {

            }
        };
    }
}
