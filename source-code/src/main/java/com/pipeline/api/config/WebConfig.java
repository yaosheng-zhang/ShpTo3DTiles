package com.pipeline.api.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web layer wiring:
 *
 *  - CORS: allow remote front-ends to call /api/** and /scene-tiles/**. The set
 *    of permitted origins is configurable via `pipeline.cors.allowed-origins`
 *    (env var CORS_ALLOWED_ORIGINS), a comma-separated list of origin patterns.
 *    The default `*` lets the JAR be consumed by ANY front-end, wherever it is
 *    hosted — which is the point of a server deployment. Lock it down to
 *    specific origins in production by setting the property.
 *  - Static resource handler: serve generated 3D-Tiles batches sitting under
 *    `${pipeline.project-root}/pipeline_v2/output/scene_tmp/<batchId>/` at the
 *    URL `/scene-tiles/<batchId>/...`. This is how the front-end TilesetLayer
 *    fetches tileset.json + b3dm chunks at runtime.
 *  - Gksvcver header: the front-end runs a vendor-customized Cesium build (蜂巢/
 *    GeoKing) whose patched XHR loader rejects EVERY 3D-Tiles response that lacks
 *    a "Gksvcver" response header — turning an otherwise-200 response into a
 *    synthetic `RequestErrorEvent(412, "{}")`. Our tiles are not served by a
 *    GeoKing host, so we stamp the header ourselves on /scene-tiles/** (see
 *    {@link #addInterceptors} / {@link #addCorsMappings}).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${pipeline.project-root:../..}")
    private String projectRootStr;

    /** Comma-separated origin patterns; `*` (default) means any origin. */
    @Value("${pipeline.cors.allowed-origins:*}")
    private String allowedOriginsStr;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // originPatterns (not origins) so wildcards and Vite's auto-bumped dev
        // ports (5173 → 5174 → …) both work. Credentials stay disabled (default),
        // which is what lets us safely use "*" — the API is token-less and the
        // tiles are public, so no cookies/auth headers ride along.
        String[] patterns = parseOrigins(allowedOriginsStr);
        registry.addMapping("/api/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
        registry.addMapping("/scene-tiles/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                // Expose Gksvcver so a cross-origin front-end's Cesium can actually
                // read it via XHR.getResponseHeader(); without this the browser hides
                // the custom header on CORS responses and the 412 check re-triggers.
                .exposedHeaders("Gksvcver")
                .maxAge(3600);
    }

    /** Split the configured list; fall back to "*" when blank. */
    private static String[] parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) return new String[]{"*"};
        String[] parts = raw.split(",");
        java.util.List<String> out = new java.util.ArrayList<>(parts.length);
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? new String[]{"*"} : out.toArray(new String[0]);
    }

    /**
     * Stamp the "Gksvcver" header onto every /scene-tiles/** response.
     *
     * The 蜂巢/GeoKing-customized Cesium loaded by the front-end intercepts each
     * 3D-Tiles XHR and, on success, demands a "Gksvcver" response header; when it
     * is absent it rejects the (HTTP-200) response with a fabricated
     * `RequestErrorEvent(412, "{}")`. Our tiles come from this plain Spring static
     * handler, so we add the header here. The GeoKing check only tests for the
     * header's PRESENCE (Cesium.defined), never its value — "1" is a safe constant
     * placeholder. preHandle runs before the resource body is written, so the
     * header reliably lands on tileset.json and every b3dm/cmpt child alike.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull Object handler) {
                response.setHeader("Gksvcver", "1");
                return true;
            }
        }).addPathPatterns("/scene-tiles/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path tilesDir = Paths.get(projectRootStr)
                .toAbsolutePath()
                .normalize()
                .resolve("pipeline_v2/output/scene_tmp");
        // Trailing slash + file: prefix is mandatory; Spring rejects raw paths.
        String location = tilesDir.toUri().toString();
        registry.addResourceHandler("/scene-tiles/**")
                .addResourceLocations(location);
    }
}
