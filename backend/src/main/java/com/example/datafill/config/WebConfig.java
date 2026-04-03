package com.example.datafill.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @org.springframework.beans.factory.annotation.Value("${data-fill.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // forward empty urls to index.html
        registry.addViewController("/")
                .setViewName("forward:/index.html");
    }

    @Override
    public void addCorsMappings(org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
        // Spring 6：当 allowCredentials=true 时，allowedOrigins 不能包含特殊值 "*"。
        // 本项目用 allowedOriginPatterns；但为了彻底规避可能的配置被当成 allowedOrigins 的情况，
        // 如果配置为 "*"，则改成更具体的 http/https 模式。
        String prop = allowedOrigins == null ? "" : allowedOrigins.trim();
        if (prop.isEmpty()) prop = "*";

        String[] patterns;
        if ("*".equals(prop)) {
            patterns = new String[] { "http://*", "https://*" };
        } else {
            patterns = Arrays.stream(prop.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .flatMap(s -> "*".equals(s) ? Arrays.stream(new String[] { "http://*", "https://*" }) : Arrays.stream(new String[] { s }))
                    .toArray(String[]::new);
        }

        // Spring 6 约束：一旦源配置里出现 "*"，允许凭证（credentials）会触发校验异常。
        // 由于本项目前端到后端通常同域/走 Nginx/Vite 代理，不依赖 CORS credentials，因此在 wildcard 情况直接关闭。
        boolean allowCredentials = !prop.contains("*");

        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(allowCredentials);
    }

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webServerCustomizer() {
        return factory -> {
            // Forward 404s to index.html to allow Vue Router to handle history mode urls
            ErrorPage error404Page = new ErrorPage(HttpStatus.NOT_FOUND, "/index.html");
            factory.addErrorPages(error404Page);
        };
    }
}
