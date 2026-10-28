package com.ctrip.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Web 层配置：跨域策略 + 登录态拦截。
 *
 * <p>跨域默认只放行本地来源（localhost/127.0.0.1 任意端口）。
 * 以 file:// 直接打开 HTML 的页面 Origin 是 {@code null}，这种来源任何本地网页都能伪装，
 * 因此单独用开关控制，默认开启只为兼容「双击本地文件演示」的用法：
 * <pre>
 *   ctrip.cors.allow-file-origin: false                    # 上线必关
 *   ctrip.cors.allowed-origins: https://travel.example.com  # 部署域名，逗号分隔，别退回 *
 * </pre>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 默认放行的本地来源；null（file://）不在此列，由下面的开关决定 */
    private static final String LOCAL_ORIGINS = "http://localhost:*,http://127.0.0.1:*";

    @Value("${ctrip.cors.allowed-origins:" + LOCAL_ORIGINS + "}")
    private String allowedOrigins;

    /** 是否接受 Origin: null（浏览器直接双击打开本地 HTML 的场景）。生产应设为 false */
    @Value("${ctrip.cors.allow-file-origin:true}")
    private boolean allowFileOrigin;

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = new ArrayList<>(Arrays.asList(allowedOrigins.split(",")));
        if (allowFileOrigin && !origins.contains("null")) origins.add("null");
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                // 显式列举允许的请求头，不再使用 *
                .allowedHeaders("Content-Type", "Authorization", "X-Token")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/orders/**", "/api/biztravel");
        // 说明：/api/biztravel 的 GET 是无个人信息的状态查询，由拦截器按方法放行，仅 POST 写入要求登录
    }
}
