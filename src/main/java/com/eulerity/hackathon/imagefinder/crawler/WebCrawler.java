package com.eulerity.hackathon.imagefinder.crawler;

import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main web crawler class responsible for crawling a website and finding all images.
 * Implements multi-threading for faster crawling and stays within the same domain.
 * Respects robots.txt for being a "friendly" crawler.
 */
public class WebCrawler {
    private final String baseUrl;
    private final String domain;
    private final Set<String> visitedUrls;
    private final Set<String> imageUrls;
    private final Map<String, ImageMetadata> imageMetadata;
    private final int maxPages;
    private final int threadCount;
    private final int crawlDelayMs;
    private final ExecutorService executor;
    private final LinkedBlockingQueue<String> urlQueue;
    private final AtomicInteger pagesCrawled;
    private final Object lock = new Object();
    private boolean isRunning;
    private RobotsTxtParser robotsTxtParser;
    
    // Define allowed schemes and content types
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
        "text/html", "application/xhtml+xml", "application/xml", "text/xml");
    
    // Maximum URL depth to crawl
    private static final int MAX_URL_DEPTH = 20;
    
    // Maximum redirects to follow
    private static final int MAX_REDIRECTS = 5;
    
    // Timeout settings
    private static final int CONNECTION_TIMEOUT_MS = 30000; // 10 seconds
    private static final int READ_TIMEOUT_MS = 60000; // 15 seconds
    
    // Map to track redirect chains to detect loops
    private final Map<String, Set<String>> redirectChains = new ConcurrentHashMap<>();
    
    // URL components to ignore in query parameters
    private static final Set<String> IGNORED_QUERY_PARAMS = Set.of(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "gclid", "msclkid", "ref", "source", "session", "timestamp");

    /**
     * Constructor for WebCrawler
     * 
     * @param url         The starting URL to crawl
     * @param maxPages    Maximum number of pages to crawl
     * @param threadCount Number of threads to use
     * @param crawlDelayMs Delay between requests in milliseconds (to be "friendly")
     */
    public WebCrawler(String url, int maxPages, int threadCount, int crawlDelayMs) {
        this.baseUrl = canonicalizeUrl(url);
        this.domain = extractDomain(this.baseUrl);
        this.visitedUrls = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.imageUrls = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.imageMetadata = new ConcurrentHashMap<>();
        this.maxPages = maxPages;
        this.threadCount = threadCount;
        this.crawlDelayMs = crawlDelayMs;
        this.executor = Executors.newFixedThreadPool(threadCount);
        this.urlQueue = new LinkedBlockingQueue<>();
        this.pagesCrawled = new AtomicInteger(0);
        this.isRunning = false;
        
        // Initialize robots.txt parser
        this.robotsTxtParser = new RobotsTxtParser(domain);
    }

    /**
     * Start the crawling process
     * 
     * @return List of image URLs found during crawling
     */
    public List<String> crawl() {
        if (isRunning) {
            return new ArrayList<>(imageUrls);
        }

        isRunning = true;
        visitedUrls.clear();
        imageUrls.clear();
        imageMetadata.clear();
        redirectChains.clear();
        pagesCrawled.set(0);

        // Add the base URL to the queue
        queueUrl(baseUrl);
        
        // Start worker threads
        for (int i = 0; i < threadCount; i++) {
            executor.submit(this::processUrlQueue);
        }

        // Wait for all tasks to complete
        try {
            executor.shutdown();
            executor.awaitTermination(60, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Crawler interrupted: " + e.getMessage());
        } finally {
            isRunning = false;
        }

        // Return the results
        return new ArrayList<>(imageUrls);
    }
    
    /**
     * Forcibly stop the crawler
     */
    public void stop() {
        if (isRunning) {
            isRunning = false;
            executor.shutdownNow();
            System.out.println("Crawler stopped by user request");
        }
    }
    
    /**
     * Process URLs from the queue until it's empty
     */
    private void processUrlQueue() {
        while (isRunning && pagesCrawled.get() < maxPages) {
            String url = null;
            try {
                // Get next URL from the queue with a timeout
                url = urlQueue.poll(1, TimeUnit.SECONDS);
                
                // If no URL is available, break
                if (url == null) {
                    // Check if other threads are still working
                    if (urlQueue.isEmpty() && pagesCrawled.get() > 0) {
                        break;
                    } else {
                        continue;
                    }
                }
                
                // Process the URL with more robust error handling
                try {
                    processSinglePage(url);
                } catch (Exception e) {
                    System.err.println("Error processing URL: " + url + " - " + e.getMessage());
                    // Continue processing other URLs even if one fails
                }
                
                // Apply crawl delay with small random jitter to be more natural
                int delay = robotsTxtParser.getCrawlDelay(crawlDelayMs);
                int jitter = new java.util.Random().nextInt(200); // Add up to 200ms jitter
                Thread.sleep(delay + jitter);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Queue a URL for crawling if it meets all criteria
     * 
     * @param url The URL to queue
     */
    private void queueUrl(String url) {
        // Skip empty or invalid URLs
        if (url == null || url.isEmpty()) {
            return;
        }
        
        // Check URL scheme
        try {
            URL urlObj = new URL(url);
            String scheme = urlObj.getProtocol();
            if (!ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
                return;
            }
        } catch (MalformedURLException e) {
            return;
        }
        
        // Check URL depth to prevent deep crawling
        int depth = getUrlDepth(url);
        if (depth > MAX_URL_DEPTH) {
            System.out.println("Skipping URL (too deep): " + url);
            return;
        }
        
        String canonicalUrl = canonicalizeUrl(url);

        // Skip if already visited or if max pages reached
        if (visitedUrls.contains(canonicalUrl) || pagesCrawled.get() >= maxPages) {
            return;
        }

        // Check if URL is in the same domain
        if (!isSameDomain(canonicalUrl)) {
            return;
        }
        
        // Check robots.txt rules
        if (!robotsTxtParser.isAllowed(canonicalUrl)) {
            System.out.println("Skipping URL (disallowed by robots.txt): " + canonicalUrl);
            return;
        }

        // Mark as visited
        synchronized (lock) {
            if (visitedUrls.add(canonicalUrl)) {
                // Add to queue for processing
                urlQueue.add(canonicalUrl);
            }
        }
    }

    /**
 * Process a single page - extract images and find links
 * 
 * @param url The URL to process
 */
private void processSinglePage(String url) {
    // Increment counter
    pagesCrawled.incrementAndGet();
    
    try {
        // Initialize redirect tracking for this URL
        Set<String> redirectsVisited = new HashSet<>();
        redirectsVisited.add(canonicalizeUrl(url));
        redirectChains.put(url, redirectsVisited);
        
        // Configure connection with custom settings
        Connection connection = Jsoup.connect(url)
                .userAgent("Eulerity-Crawler/1.0")
                .timeout(CONNECTION_TIMEOUT_MS)
                .maxBodySize(1024 * 1024) // 1MB max body size
                .followRedirects(false) // Handle redirects manually
                .ignoreContentType(true) // Check content type ourselves
                .ignoreHttpErrors(false);
        
        // Use retry mechanism first
        Connection.Response response = null;
        try {
            response = executeWithRetry(connection, 3); // Try up to 3 times
            if (response == null) {
                return; // Failed to get response after retries
            }
            
            // Continue with redirect handling
            response = executeRequestWithRedirects(connection, url, MAX_REDIRECTS);
            if (response == null) {
                return; // Failed to get response after redirects
            }
        } catch (IOException e) {
            System.err.println("Error processing URL: " + url + " - " + e.getMessage());
            return;
        }
        
        // Get the final URL after possible redirects
        String finalUrl = response.url().toString();
        String canonicalFinalUrl = canonicalizeUrl(finalUrl);
        
        // If the URL was redirected, update the visited URLs
        if (!url.equals(finalUrl)) {
            // Add the redirect target to visited URLs
            synchronized (lock) {
                visitedUrls.add(canonicalFinalUrl);
            }
            
            // Check if the redirect target is in the same domain
            if (!isSameDomain(canonicalFinalUrl)) {
                System.out.println("Skipping URL (redirect to different domain): " + canonicalFinalUrl);
                return;
            }
        }
        
        // Check content type
        String contentType = response.contentType();
        if (contentType == null || !isAllowedContentType(contentType)) {
            System.out.println("Skipping URL (disallowed content type: " + contentType + "): " + url);
            return;
        }
        
        // Parse the document from the response
        Document document = response.parse();

        // Extract images
        extractImages(document, url);

        // Extract links for further crawling
        extractLinks(document);

    } catch (SocketTimeoutException e) {
        System.err.println("Error processing URL: " + url + " - Read timed out");
    } catch (HttpStatusException e) {
        System.err.println("Error processing URL: " + url + " - HTTP error fetching URL");
    } catch (IOException e) {
        System.err.println("Error processing URL: " + url + " - " + e.getMessage());
    } catch (Exception e) {
        System.err.println("Unexpected error processing URL: " + url + " - " + e.getMessage());
    } finally {
        // Clean up redirect tracking for this URL
        redirectChains.remove(url);
    }
}
    
    /**
 * Execute a request and follow redirects manually with improved loop detection
 * 
 * @param connection The JSoup connection
 * @param originalUrl The original URL
 * @param maxRedirects Maximum number of redirects to follow
 * @return The final response or null if failed
 */
private Connection.Response executeRequestWithRedirects(Connection connection, String originalUrl, int maxRedirects) 
    throws IOException {
    String currentUrl = originalUrl;
    Set<String> visitedUrls = new HashSet<>();
    
    for (int redirectCount = 0; redirectCount < maxRedirects; redirectCount++) {
        try {
            // Execute the request
            Connection.Response response = connection.execute();
            
            int statusCode = response.statusCode();
            
            // Check if it's a redirect status code
            if (!(statusCode >= 300 && statusCode < 400)) {
                return response; // Not a redirect, return the response
            }
            
            // Get the redirect location
            String location = response.header("Location");
            if (location == null || location.isEmpty()) {
                return response; // No valid redirect location
            }
            
            // Resolve relative redirects
            URL base = new URL(currentUrl);
            URL redirectUrl = new URL(base, location);
            String redirectUrlStr = redirectUrl.toString();
            
            // Normalize the URLs for better comparison
            String normalizedRedirectUrl = normalizeUrl(redirectUrlStr);
            
            // Better redirect loop detection with normalized URLs
            if (visitedUrls.contains(normalizedRedirectUrl)) {
                System.out.println("Potential redirect loop detected. Stopping at: " + redirectUrlStr);
                return response; // Return the last valid response instead of null
            }
            
            // Add to visited URLs
            visitedUrls.add(normalizedRedirectUrl);
            
            // Update current URL and connection
            currentUrl = redirectUrlStr;
            
            // Apply a progressive delay between redirects
            try {
                Thread.sleep(Math.min(200 * (redirectCount + 1), 2000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            connection = Jsoup.connect(currentUrl)
                    .userAgent("Mozilla/5.0 (compatible; EulerityBot/1.0)")
                    .timeout(CONNECTION_TIMEOUT_MS)
                    .maxBodySize(1024 * 1024)
                    .followRedirects(false)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(false);
            
        } catch (IOException e) {
            // If we've already followed some redirects, log and retry with increased timeout
            if (redirectCount > 0) {
                System.err.println("Error during redirect handling: " + e.getMessage());
                try {
                    // Apply backoff before retry
                    Thread.sleep(1000);
                    // Retry with increased timeout
                    connection = connection.timeout(CONNECTION_TIMEOUT_MS * 2);
                    return connection.execute();
                } catch (IOException finalError) {
                    throw finalError;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during redirect handling");
                }
            } else {
                throw e;
            }
        }
    }
    
    // If we get here, we've hit the maximum redirects
    System.err.println("Maximum redirects reached for URL: " + currentUrl);
    return null;
}
    /**
 * Execute a request with retry mechanism
 * 
 * @param connection The JSoup connection
 * @param maxRetries Maximum number of retries
 * @return The response or null if all retries failed
 * @throws IOException If an I/O error occurs
 */
private Connection.Response executeWithRetry(Connection connection, int maxRetries) throws IOException {
    IOException lastException = null;
    
    for (int attempt = 0; attempt < maxRetries; attempt++) {
        try {
            // Apply backoff if this is a retry
            if (attempt > 0) {
                try {
                    // Exponential backoff with jitter
                    long backoffMs = Math.min(1000 * (long)Math.pow(2, attempt - 1), 10000);
                    backoffMs += new java.util.Random().nextInt(1000); // Add jitter
                    Thread.sleep(backoffMs);
                    System.out.println("Retrying request (attempt " + (attempt + 1) + "/" + maxRetries + ")");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry backoff");
                }
            }
            
            return connection.execute();
        } catch (IOException e) {
            lastException = e;
            System.err.println("Request failed (attempt " + (attempt + 1) + "/" + maxRetries + "): " + e.getMessage());
            
            // Increase timeout for next attempt
            connection = connection.timeout(CONNECTION_TIMEOUT_MS * (attempt + 2));
        }
    }
    
    // All retries failed
    throw lastException != null ? lastException : new IOException("All retries failed");
}
    
    /**
     * Check if the content type is allowed for parsing
     * 
     * @param contentType The content type to check
     * @return true if allowed, false otherwise
     */
    private boolean isAllowedContentType(String contentType) {
        // Remove charset and other parameters
        if (contentType.contains(";")) {
            contentType = contentType.substring(0, contentType.indexOf(";")).trim();
        }
        
        for (String allowedType : ALLOWED_CONTENT_TYPES) {
            if (contentType.toLowerCase().startsWith(allowedType)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Extract all images from the document and add to the result set
     * 
     * @param document The HTML document
     * @param pageUrl The URL of the page where the images were found
     */
    private void extractImages(Document document, String pageUrl) {
        // Find all img tags
        Elements imgElements = document.select("img");
        for (Element img : imgElements) {
            String imageUrl = img.absUrl("src");
            if (!imageUrl.isEmpty()) {
                addImage(imageUrl, img, pageUrl);
            }
            
            // Check for lazy-loaded images in data attributes
            String dataSrc = img.attr("data-src");
            if (!dataSrc.isEmpty()) {
                String fullDataSrc = resolveUrl(pageUrl, dataSrc);
                if (!fullDataSrc.isEmpty()) {
                    addImage(fullDataSrc, img, pageUrl);
                }
            }
            
            // Check other common data attributes for images
            String[] dataAttrs = {"data-original", "data-lazy-src", "data-srcset", "data-lazy"};
            for (String attr : dataAttrs) {
                String attrValue = img.attr(attr);
                if (!attrValue.isEmpty()) {
                    String fullAttrSrc = resolveUrl(pageUrl, attrValue);
                    if (!fullAttrSrc.isEmpty()) {
                        addImage(fullAttrSrc, img, pageUrl);
                    }
                }
            }
            
            // Check srcset attribute
            String srcset = img.attr("srcset");
            if (!srcset.isEmpty()) {
                extractImagesFromSrcset(srcset, img, pageUrl);
            }
        }

        // Look for CSS background images (limited to style attributes for simplicity)
        Elements elementsWithStyle = document.select("[style]");
        for (Element element : elementsWithStyle) {
            String style = element.attr("style");
            if (style.contains("background-image")) {
                extractImageUrlFromStyle(style, pageUrl);
            }
        }
        
        // Look for image links
        Elements linkElements = document.select("a[href]");
        for (Element link : linkElements) {
            String href = link.absUrl("href");
            // Check if the link points to an image file
            if (isImageUrl(href)) {
                addImage(href, null, pageUrl);
            }
        }
    }
    
    /**
     * Extract images from srcset attribute
     * 
     * @param srcset The srcset attribute value
     * @param imgElement The img element
     * @param pageUrl The page URL
     */
    private void extractImagesFromSrcset(String srcset, Element imgElement, String pageUrl) {
        // Split by commas (separates different image definitions)
        String[] srcSetParts = srcset.split(",");
        
        for (String part : srcSetParts) {
            // Extract the URL (ignoring the descriptor)
            String[] spaceSplit = part.trim().split("\\s+");
            if (spaceSplit.length > 0) {
                String imageUrl = spaceSplit[0].trim();
                String fullImageUrl = resolveUrl(pageUrl, imageUrl);
                if (!fullImageUrl.isEmpty()) {
                    addImage(fullImageUrl, imgElement, pageUrl);
                }
            }
        }
    }

    /**
     * Add an image to the results with metadata
     * 
     * @param imageUrl The URL of the image
     * @param imgElement The img element (may be null for CSS background images)
     * @param pageUrl The URL of the page where the image was found
     */
    private void addImage(String imageUrl, Element imgElement, String pageUrl) {
        // Skip empty or invalid image URLs
        if (imageUrl == null || imageUrl.isEmpty()) {
            return;
        }
        
        // Skip data URLs
        if (imageUrl.startsWith("data:")) {
            return;
        }
        
        // Canonicalize image URL
        imageUrl = canonicalizeUrl(imageUrl);
        
        // Only add if it's a new image
        if (!imageUrls.contains(imageUrl)) {
            synchronized (lock) {
                // Check again in case another thread added it
                if (!imageUrls.add(imageUrl)) {
                    return;
                }
            }
            
            // Create metadata
            ImageMetadata metadata = new ImageMetadata(imageUrl);
            metadata.setPageFound(pageUrl);
            
            // Extract additional metadata if available
            if (imgElement != null) {
                // Extract alt text
                String altText = imgElement.attr("alt");
                if (!altText.isEmpty()) {
                    metadata.setAltText(altText);
                }
                
                // Extract dimensions if available
                String width = imgElement.attr("width");
                String height = imgElement.attr("height");
                if (!width.isEmpty()) {
                    try {
                        metadata.setWidth(Integer.parseInt(width));
                    } catch (NumberFormatException e) {
                        // Ignore invalid width
                    }
                }
                if (!height.isEmpty()) {
                    try {
                        metadata.setHeight(Integer.parseInt(height));
                    } catch (NumberFormatException e) {
                        // Ignore invalid height
                    }
                }
                
                // Check if it's a logo using the enhanced detection
                metadata.setLogo(LogoDetector.isLikelyLogo(
                        imageUrl, 
                        metadata.getWidth(), 
                        metadata.getHeight(), 
                        metadata.getAltText(),
                        pageUrl));
            } else {
                // For non-img elements, check if it's a logo using URL and page context
                metadata.setLogo(LogoDetector.isLikelyLogo(imageUrl, -1, -1, null, pageUrl));
            }
            
            // Store metadata
            imageMetadata.put(imageUrl, metadata);
        }
    }

    /**
     * Extract image URL from CSS style attribute
     * 
     * @param style The CSS style string
     * @param pageUrl The URL of the page where the image was found
     */
    private void extractImageUrlFromStyle(String style, String pageUrl) {
        // Simple regex to find url() in background-image
        int startIndex = style.indexOf("url(");
        if (startIndex != -1) {
            startIndex += 4;
            int endIndex = style.indexOf(")", startIndex);
            if (endIndex != -1) {
                String url = style.substring(startIndex, endIndex).trim();
                // Remove quotes if present
                if ((url.startsWith("\"") && url.endsWith("\"")) || 
                    (url.startsWith("'") && url.endsWith("'"))) {
                    url = url.substring(1, url.length() - 1);
                }
                if (!url.isEmpty() && (url.startsWith("http") || url.startsWith("/") || url.startsWith("./") || url.startsWith("../"))) {
                    // Skip data URLs
                    if (url.startsWith("data:")) {
                        return;
                    }
                    
                    // Convert relative URLs to absolute
                    String fullUrl = resolveUrl(pageUrl, url);
                    if (!fullUrl.isEmpty()) {
                        addImage(fullUrl, null, pageUrl);
                    }
                }
            }
        }
    }
    
    /**
     * Resolve a relative URL against a base URL
     * 
     * @param baseUrl The base URL
     * @param relativeUrl The relative URL
     * @return The resolved URL or empty string if invalid
     */
    private String resolveUrl(String baseUrl, String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isEmpty()) {
            return "";
        }
        
        // Already absolute
        if (relativeUrl.startsWith("http")) {
            return relativeUrl;
        }
        
        try {
            URL base = new URL(baseUrl);
            URL resolved = new URL(base, relativeUrl);
            return resolved.toString();
        } catch (MalformedURLException e) {
            return "";
        }
    }
    
    /**
     * Check if a URL points to an image file
     * 
     * @param url The URL to check
     * @return true if it's an image URL, false otherwise
     */
    private boolean isImageUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        String lowerUrl = url.toLowerCase();
        return lowerUrl.endsWith(".jpg") || 
               lowerUrl.endsWith(".jpeg") || 
               lowerUrl.endsWith(".png") || 
               lowerUrl.endsWith(".gif") || 
               lowerUrl.endsWith(".webp") || 
               lowerUrl.endsWith(".svg") || 
               lowerUrl.endsWith(".bmp") || 
               lowerUrl.endsWith(".ico");
    }

    /**
     * Extract all links from the document for further crawling
     * 
     * @param document The HTML document
     */
    private void extractLinks(Document document) {
        // Extract regular links
        Elements linkElements = document.select("a[href]");
        for (Element link : linkElements) {
            String linkUrl = link.absUrl("href");
            // Skip empty links, javascript:, mailto:, tel:, etc.
            if (linkUrl.isEmpty() || linkUrl.startsWith("javascript:") || 
                linkUrl.startsWith("mailto:") || linkUrl.startsWith("tel:") || 
                linkUrl.startsWith("#")) {
                continue;
            }
            
            // Skip image URLs as they've already been processed
            if (!isImageUrl(linkUrl)) {
                queueUrl(linkUrl);
            }
        }
        
        // Extract iframe sources
        Elements iframeElements = document.select("iframe[src]");
        for (Element iframe : iframeElements) {
            String srcUrl = iframe.absUrl("src");
            if (!srcUrl.isEmpty()) {
                queueUrl(srcUrl);
            }
        }
        
        // Extract form actions
        Elements formElements = document.select("form[action]");
        for (Element form : formElements) {
            String actionUrl = form.absUrl("action");
            if (!actionUrl.isEmpty()) {
                queueUrl(actionUrl);
            }
        }
    }
    
    /**
     * Get image metadata for all discovered images
     * 
     * @return A map of image URLs to their metadata
     */
    public Map<String, ImageMetadata> getImageMetadata() {
        return new HashMap<>(imageMetadata);
    }
    
    /**
     * Get metadata for a specific image
     * 
     * @param imageUrl The image URL
     * @return The metadata, or null if not found
     */
    public ImageMetadata getImageMetadata(String imageUrl) {
        return imageMetadata.get(imageUrl);
    }

    /**
     * Check if a URL is in the same domain as the base URL
     * 
     * @param url The URL to check
     * @return true if in the same domain, false otherwise
     */
    private boolean isSameDomain(String url) {
        try {
            URL urlObj = new URL(url);
            String urlDomain = normalizeHost(urlObj.getHost());
            URL baseUrlObj = new URL(domain);
            String baseDomain = normalizeHost(baseUrlObj.getHost());
            
            return urlObj.getProtocol().equals(baseUrlObj.getProtocol()) && 
                  urlDomain.equals(baseDomain);
        } catch (MalformedURLException e) {
            return false;
        }
    }
    
    /**
     * Normalize hostname (e.g., remove www prefix)
     * 
     * @param host The hostname to normalize
     * @return Normalized hostname
     */
    private String normalizeHost(String host) {
        if (host.startsWith("www.")) {
            return host.substring(4);
        }
        return host;
    }

    /**
     * Extract domain from URL
     * 
     * @param url The URL
     * @return The domain part of the URL
     */
    private String extractDomain(String url) {
        try {
            URL urlObj = new URL(url);
            String protocol = urlObj.getProtocol();
            String host = normalizeHost(urlObj.getHost());
            return protocol + "://" + host;
        } catch (MalformedURLException e) {
            // If URL is malformed, return the original URL as a fallback
            return url;
        }
    }
    
    /**
     * Get the depth of a URL (number of path segments)
     * 
     * @param url The URL to check
     * @return The depth
     */
    private int getUrlDepth(String url) {
        try {
            URL urlObj = new URL(url);
            String path = urlObj.getPath();
            if (path == null || path.isEmpty() || path.equals("/")) {
                return 0;
            }
            
            // Count slashes in the path
            int depth = 0;
            for (int i = 0; i < path.length(); i++) {
                if (path.charAt(i) == '/') {
                    depth++;
                }
            }
            return depth;
        } catch (MalformedURLException e) {
            return 0;
        }
    }

    /**
     * Canonicalize URL to ensure consistent representation
     * 
     * @param url The URL to canonicalize
     * @return Canonicalized URL
     */
    private String canonicalizeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        
        try {
            // Ensure URL has protocol
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            
            // Parse URL
            URL urlObj = new URL(url);
            String protocol = urlObj.getProtocol();
            String host = normalizeHost(urlObj.getHost());
            int port = urlObj.getPort();
            String path = urlObj.getPath();
            String query = urlObj.getQuery();
            
            // Normalize path: ensure it starts with / and handle default pages
            if (path == null || path.isEmpty()) {
                path = "/";
            } else if (path.endsWith("/index.html") || path.endsWith("/index.php") || 
                      path.endsWith("/index.asp") || path.endsWith("/index.jsp") ||
                      path.endsWith("/default.html") || path.endsWith("/default.php") ||
                      path.endsWith("/default.asp") || path.endsWith("/default.jsp") ||
                      path.endsWith("/home.html") || path.endsWith("/home.php") ||
                      path.endsWith("/home.asp") || path.endsWith("/home.jsp")) {
                // Replace with directory root
                path = path.substring(0, path.lastIndexOf('/') + 1);
            }
            
            // Remove trailing slash from path except for root
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            
            // Clean query parameters
            String cleanQuery = cleanQueryParameters(query);
            
            // Build canonicalized URL
            StringBuilder result = new StringBuilder();
            result.append(protocol).append("://").append(host);
            
            if (port != -1 && port != 80 && port != 443) {
                result.append(":").append(port);
            }
            
            result.append(path);
            
            if (cleanQuery != null && !cleanQuery.isEmpty()) {
                result.append("?").append(cleanQuery);
            }
            
            return result.toString();
            
        } catch (MalformedURLException e) {
            // If parsing fails, return normalized version
            return normalizeUrl(url);
        }
    }
    
    /**
     * Clean query parameters by removing tracking parameters
     * 
     * @param query The query string
     * @return Cleaned query string
     */
    private String cleanQueryParameters(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        
        StringBuilder result = new StringBuilder();
        String[] pairs = query.split("&");
        boolean first = true;
        
        for (String pair : pairs) {
            // Skip empty pairs
            if (pair.isEmpty()) {
                continue;
            }
            
            // Split parameter name and value
            String[] parts = pair.split("=", 2);
            String name = parts[0];
            
            // Skip ignored parameters
            if (IGNORED_QUERY_PARAMS.contains(name.toLowerCase())) {
                continue;
            }
            
            // Add parameter to result
            if (!first) {
                result.append("&");
            }
            result.append(pair);
            first = false;
        }
        
        return result.toString();
    }
    
    
    /**
 * Normalize URL by removing fragments, query parameters, and standardizing format
 * 
 * @param url The URL to normalize
 * @return Normalized URL
 */
private String normalizeUrl(String url) {
    try {
        // Remove fragments
        URL urlObj = new URL(url);
        String protocol = urlObj.getProtocol();
        String host = normalizeHost(urlObj.getHost());
        String path = urlObj.getPath();
        
        // Normalize path
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        
        // Remove trailing slash for consistency, except for root
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        
        // Reconstruct URL without query and fragment
        return protocol + "://" + host + path;
        
    } catch (MalformedURLException e) {
        // Fallback to basic normalization
        url = url.toLowerCase().trim();
        int fragmentIndex = url.indexOf('#');
        if (fragmentIndex > 0) {
            url = url.substring(0, fragmentIndex);
        }
        
        // Remove trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        
        return url;
    }
}

    /**
     * Check if the crawler is currently running
     * 
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Get the current count of pages crawled
     * 
     * @return Number of pages crawled
     */
    public int getPagesCrawled() {
        return pagesCrawled.get();
    }
    
    /**
     * Get the set of visited URLs
     * 
     * @return Set of visited URLs
     */
    public Set<String> getVisitedUrls() {
        return new HashSet<>(visitedUrls);
    }
}