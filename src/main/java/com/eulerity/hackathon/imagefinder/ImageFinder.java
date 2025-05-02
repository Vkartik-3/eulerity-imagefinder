// package com.eulerity.hackathon.imagefinder;

// import java.io.IOException;

// import javax.servlet.ServletException;
// import javax.servlet.annotation.WebServlet;
// import javax.servlet.http.HttpServlet;
// import javax.servlet.http.HttpServletRequest;
// import javax.servlet.http.HttpServletResponse;

// import com.google.gson.Gson;
// import com.google.gson.GsonBuilder;

// @WebServlet(
//     name = "ImageFinder",
//     urlPatterns = {"/main"}
// )
// public class ImageFinder extends HttpServlet{
// 	private static final long serialVersionUID = 1L;

// 	protected static final Gson GSON = new GsonBuilder().create();

// 	//This is just a test array
// 	public static final String[] testImages = {
// 			"https://images.pexels.com/photos/545063/pexels-photo-545063.jpeg?auto=compress&format=tiny",
// 			"https://images.pexels.com/photos/464664/pexels-photo-464664.jpeg?auto=compress&format=tiny",
// 			"https://images.pexels.com/photos/406014/pexels-photo-406014.jpeg?auto=compress&format=tiny",
// 			"https://images.pexels.com/photos/1108099/pexels-photo-1108099.jpeg?auto=compress&format=tiny"
//   };

// 	@Override
// 	protected final void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
// 		resp.setContentType("text/json");
// 		String path = req.getServletPath();
// 		String url = req.getParameter("url");
// 		System.out.println("Got request of:" + path + " with query param:" + url);
// 		resp.getWriter().print(GSON.toJson(testImages));
// 	}
// }

package com.eulerity.hackathon.imagefinder;

import java.io.IOException;
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

@WebServlet(
    name = "ImageFinder",
    urlPatterns = {"/main"}
)
public class ImageFinder extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_MAX_PAGES = 100;
    private static final int DEFAULT_THREAD_COUNT = 8;
    private static final int DEFAULT_CRAWL_DELAY_MS = 200; // 200ms delay to be "friendly"

    // Map to store active crawlers
    private static final Map<String, WebCrawler> activeCrawlers = new ConcurrentHashMap<>();
    
    // Map to store crawling results
    private static final Map<String, List<ImageResult>> resultsCache = new ConcurrentHashMap<>();

    protected static final Gson GSON = new GsonBuilder().create();

    @Override
    protected final void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json");
        String path = req.getServletPath();
        String url = req.getParameter("url");
        String action = req.getParameter("action"); // For status checks
        
        // Parse optional parameters
        int maxPages = parseIntParam(req, "maxPages", DEFAULT_MAX_PAGES);
        int threadCount = parseIntParam(req, "threadCount", DEFAULT_THREAD_COUNT);
        int crawlDelay = parseIntParam(req, "crawlDelay", DEFAULT_CRAWL_DELAY_MS);
        boolean detectLogos = Boolean.parseBoolean(req.getParameter("detectLogos"));
        
        System.out.println("Got request of: " + path + " with query param: " + url + ", action: " + action);
        
        // Return status if requested
        if ("status".equals(action) && url != null) {
            handleStatusRequest(url, resp);
            return;
        }
        
        // Check if URL is provided
        if (url == null || url.isEmpty()) {
            sendErrorResponse(resp, "URL parameter is required");
            return;
        }
        
        // Check if a crawler is already running for this URL
        if (activeCrawlers.containsKey(url) && activeCrawlers.get(url).isRunning()) {
            sendErrorResponse(resp, "Crawling already in progress for this URL");
            return;
        }
        
        // Check if we have cached results
        if (resultsCache.containsKey(url)) {
            resp.getWriter().print(GSON.toJson(resultsCache.get(url)));
            return;
        }
        
        // Create and start a new crawler
        WebCrawler crawler = new WebCrawler(url, maxPages, threadCount, crawlDelay);
        activeCrawlers.put(url, crawler);
        
        try {
            // Start crawling
            List<String> imageUrls = crawler.crawl();
            Map<String, ImageMetadata> metadataMap = crawler.getImageMetadata();
            
            // Process results
            List<ImageResult> results = new ArrayList<>();
            for (String imageUrl : imageUrls) {
                ImageMetadata metadata = metadataMap.get(imageUrl);
                if (metadata != null) {
                    ImageResult result = new ImageResult(
                            imageUrl,
                            metadata.isLogo(),
                            metadata.getAltText(),
                            metadata.getWidth(),
                            metadata.getHeight(),
                            metadata.getPageFound()
                    );
                    results.add(result);
                } else {
                    // Fallback if metadata is missing
                    ImageResult result = new ImageResult(imageUrl);
                    if (detectLogos) {
                        result.setLogo(LogoDetector.isLikelyLogo(imageUrl));
                    }
                    results.add(result);
                }
            }
            
            // Cache the results
            resultsCache.put(url, results);
            
            // Send the results back
            resp.getWriter().print(GSON.toJson(results));
            
        } catch (Exception e) {
            sendErrorResponse(resp, "Error during crawling: " + e.getMessage());
        } finally {
            // Remove the crawler from active crawlers
            activeCrawlers.remove(url);
        }
    }
    
    /**
     * Handle status check requests
     */
    private void handleStatusRequest(String url, HttpServletResponse resp) throws IOException {
        Map<String, Object> status = new HashMap<>();
        
        WebCrawler crawler = activeCrawlers.get(url);
        if (crawler != null && crawler.isRunning()) {
            status.put("status", "running");
            status.put("pagesCrawled", crawler.getPagesCrawled());
            status.put("visitedUrls", crawler.getVisitedUrls().size());
        } else if (resultsCache.containsKey(url)) {
            status.put("status", "completed");
            status.put("resultsCount", resultsCache.get(url).size());
        } else {
            status.put("status", "not_found");
        }
        
        resp.getWriter().print(GSON.toJson(status));
    }
    
    /**
     * Send an error response
     */
    private void sendErrorResponse(HttpServletResponse resp, String message) throws IOException {
        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        resp.getWriter().print(GSON.toJson(error));
    }
    
    /**
     * Parse an integer parameter with a default value
     */
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
    
    /**
     * Inner class to represent an image result with metadata
     */
    public static class ImageResult {
        private String url;
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
        
        public String getUrl() {
            return url;
        }
        
        public boolean isLogo() {
            return isLogo;
        }
        
        public void setLogo(boolean isLogo) {
            this.isLogo = isLogo;
        }
        
        public String getAltText() {
            return altText;
        }
        
        public int getWidth() {
            return width;
        }
        
        public int getHeight() {
            return height;
        }
        
        public String getPageFound() {
            return pageFound;
        }
    }
}