package com.ctrip.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：跨域策略 + 登录态拦截。
 *
 * <p>跨域默认只放行本地来源（file:// 打开的页面 Origin 为 null，以及 localhost/127.0.0.1 任意端口）。
 * 部署到其它域名时通过配置覆盖，不要退回 *：
 * <pre>
 *   ctrip.cors.allowed-origins: https://travel.example.com,https://www.example.com
 * </pre>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 允许的本地来源；null 对应浏览器以 file:// 直接打开 HTML 的场景 */
    private static final String LOCAL_ORIGINS = "null,http://localhost:*,http://127.0.0.1:*";

    @Value("${ctrip.cors.allowed-origins:" + LOCAL_ORIGINS + "}")
    private String allowedOrigins;

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
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
