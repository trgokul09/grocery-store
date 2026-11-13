package com.grocery.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import java.io.IOException;

/**
 * Serves the built React app from classpath:/static (used by the single-container / Render deployment).
 * Any non-API path that is not a real file falls back to index.html so React Router deep links and refreshes work.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**").addResourceLocations("classpath:/static/").resourceChain(true)
            .addResolver(new PathResourceResolver() {
                @Override
                protected Resource getResource(String resourcePath, Resource location) throws IOException {
                    if (resourcePath.startsWith("api/")) return null;
                    Resource requested = location.createRelative(resourcePath);
                    return requested.exists() && requested.isReadable() ? requested : new ClassPathResource("/static/index.html");
                }
            });
    }
}
