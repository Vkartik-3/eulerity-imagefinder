package com.eulerity.hackathon.imagefinder;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.eulerity.hackathon.imagefinder.crawler.ImageMetadata;
import com.eulerity.hackathon.imagefinder.crawler.LogoDetector;
import com.eulerity.hackathon.imagefinder.crawler.WebCrawler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

@WebServlet(name = "ImageFinder", urlPatterns = {"/main"})
public class ImageFinder extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_MAX_PAGES = 100;
    private static final int DEFAULT_THREAD_COUNT = 8;
    private static final int DEFAULT_CRAWL_DELAY_MS = 200;

    private static final Map<String, WebCrawler> activeCrawlers = new ConcurrentHashMap<>();
    private static final Map<String, List<ImageResult>> resultsCache = new ConcurrentHashMap<>();

    protected static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .create();

    @Override
    protected final void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json");
        String url = req.getParameter("url");
        String action = req.getParameter("action");

        // Handle different actions
        if (action != null) {
            switch (action) {
                case "status":
                    handleStatusRequest(url, resp);
                    return;
                case "stop":
                    handleStopRequest(url, resp);
                    return;
                case "clearCache":
                    handleClearCacheRequest(url, resp);
                    return;
            }
        }

        // Validate URL
        if (url == null || url.isEmpty() || !isValidUrl(url)) {
            sendErrorResponse(resp, "Invalid or missing URL");
            return;
        }

        // Prepare crawling parameters
        int maxPages = parseIntParam(req, "maxPages", DEFAULT_MAX_PAGES);
        int threadCount = parseIntParam(req, "threadCount", DEFAULT_THREAD_COUNT);
        int crawlDelay = parseIntParam(req, "crawlDelay", DEFAULT_CRAWL_DELAY_MS);
        boolean detectLogos = Boolean.parseBoolean(req.getParameter("detectLogos"));
        boolean refresh = Boolean.parseBoolean(req.getParameter("refresh"));

        try {
            // Check cache
            if (!refresh && resultsCache.containsKey(url)) {
                resp.getWriter().print(GSON.toJson(resultsCache.get(url)));
                return;
            }

            // Create and start crawler
            WebCrawler crawler = new WebCrawler(url, maxPages, threadCount, crawlDelay);
            activeCrawlers.put(url, crawler);

            // Crawl and process images
            List<String> imageUrls = crawler.crawl();
            Map<String, ImageMetadata> metadataMap = crawler.getImageMetadata();
            
            List<ImageResult> results = new ArrayList<>();
            for (String imageUrl : imageUrls) {
                ImageMetadata metadata = metadataMap.get(imageUrl);
                ImageResult result = metadata != null 
                    ? new ImageResult(
                        imageUrl, 
                        metadata.isLogo(), 
                        metadata.getAltText(), 
                        metadata.getWidth(), 
                        metadata.getHeight(), 
                        metadata.getPageFound()
                    )
                    : createFallbackResult(imageUrl, detectLogos);
                
                results.add(result);
            }

            // Cache and return results
            resultsCache.put(url, results);
            resp.getWriter().print(GSON.toJson(results));

        } catch (Exception e) {
            sendErrorResponse(resp, "Crawling error: " + e.getMessage());
        } finally {
            // Cleanup crawler
            WebCrawler crawler = activeCrawlers.get(url);
            if (crawler != null && !crawler.isRunning()) {
                activeCrawlers.remove(url);
            }
        }
    }

    private ImageResult createFallbackResult(String imageUrl, boolean detectLogos) {
        ImageResult result = new ImageResult(imageUrl);
        if (detectLogos) {
            result.setLogo(LogoDetector.isLikelyLogo(imageUrl));
        }
        return result;
    }

    private void handleStatusRequest(String url, HttpServletResponse resp) throws IOException {
        Map<String, Object> status = new HashMap<>();
        WebCrawler crawler = activeCrawlers.get(url);
        
        if (crawler != null && crawler.isRunning()) {
            status.put("status", "running");
            status.put("pagesCrawled", crawler.getPagesCrawled());
        } else if (resultsCache.containsKey(url)) {
            status.put("status", "completed");
            status.put("resultsCount", resultsCache.get(url).size());
        } else {
            status.put("status", "not_found");
        }
        
        resp.getWriter().print(GSON.toJson(status));
    }

    private void handleStopRequest(String url, HttpServletResponse resp) throws IOException {
        Map<String, Object> result = new HashMap<>();
        WebCrawler crawler = activeCrawlers.get(url);
        
        if (crawler != null && crawler.isRunning()) {
            crawler.stop();
            result.put("status", "stopped");
        } else {
            result.put("status", "not_running");
        }
        
        resp.getWriter().print(GSON.toJson(result));
    }

    private void handleClearCacheRequest(String url, HttpServletResponse resp) throws IOException {
        Map<String, Object> result = new HashMap<>();
        
        if ("all".equals(url)) {
            resultsCache.clear();
            result.put("status", "success");
        } else if (resultsCache.containsKey(url)) {
            resultsCache.remove(url);
            result.put("status", "success");
        } else {
            result.put("status", "not_found");
        }
        
        resp.getWriter().print(GSON.toJson(result));
    }

    private boolean isValidUrl(String url) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            new URL(url);
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private void sendErrorResponse(HttpServletResponse resp, String message) throws IOException {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        resp.getWriter().print(GSON.toJson(error));
    }

    private int parseIntParam(HttpServletRequest req, String paramName, int defaultValue) {
        String paramValue = req.getParameter(paramName);
        if (paramValue != null && !paramValue.isEmpty()) {
            try {
                return Integer.parseInt(paramValue);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static class ImageResult {
        private String url;
        
        @SerializedName("isLogo")
        private boolean isLogo;
        
        private String altText;
        private int width;
        private int height;
        private String pageFound;
        
        public ImageResult(String url) {
            this.url = url;
            this.isLogo = false;
            this.width = -1;
            this.height = -1;
        }
        
        public ImageResult(String url, boolean isLogo, String altText, int width, int height, String pageFound) {
            this.url = url;
            this.isLogo = isLogo;
            this.altText = altText;
            this.width = width;
            this.height = height;
            this.pageFound = pageFound;
        }
        
        // Getters and setters remain the same as in previous implementation
        public String getUrl() { return url; }
        public boolean isLogo() { return isLogo; }
        public void setLogo(boolean isLogo) { this.isLogo = isLogo; }
        public String getAltText() { return altText; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public String getPageFound() { return pageFound; }
    }
}