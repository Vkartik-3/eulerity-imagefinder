// package com.eulerity.hackathon.imagefinder;


// import java.io.IOException;
// import java.io.PrintWriter;
// import java.io.StringWriter;

// import org.junit.Assert;
// import org.junit.Test;

// import javax.servlet.ServletException;
// import javax.servlet.http.HttpServletRequest;
// import javax.servlet.http.HttpServletResponse;
// import javax.servlet.http.HttpSession;

// import org.junit.Before;
// import org.mockito.Mockito;

// import com.eulerity.hackathon.imagefinder.ImageFinder;
// import com.google.gson.Gson;

// public class ImageFinderTest {

// 	public HttpServletRequest request;
// 	public HttpServletResponse response;
// 	public StringWriter sw;
// 	public HttpSession session;

// 	@Before
// 	public void setUp() throws Exception {
// 		request = Mockito.mock(HttpServletRequest.class);
// 		response = Mockito.mock(HttpServletResponse.class);
//     sw = new StringWriter();
//     PrintWriter pw = new PrintWriter(sw);
// 		Mockito.when(response.getWriter()).thenReturn(pw);
// 		Mockito.when(request.getRequestURI()).thenReturn("/foo/foo/foo");
// 		Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/foo/foo/foo"));
// 		session = Mockito.mock(HttpSession.class);
// 		Mockito.when(request.getSession()).thenReturn(session);
// 	}
	
//   @Test
//   public void test() throws IOException, ServletException {
// 		Mockito.when(request.getServletPath()).thenReturn("/main");
// 		new ImageFinder().doPost(request, response);
// 		Assert.assertEquals(new Gson().toJson(ImageFinder.testImages), sw.toString());
//   }
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
            
            // Process results
            List<ImageResult> results = new ArrayList<>();
            for (String imageUrl : imageUrls) {
                ImageResult result = new ImageResult(imageUrl);
                
                // Detect if the image is a logo if enabled
                if (detectLogos) {
                    result.setLogo(LogoDetector.isLikelyLogo(imageUrl));
                }
                
                results.add(result);
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
        
        public ImageResult(String url) {
            this.url = url;
            this.isLogo = false;
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
    }
}

