package com.eulerity.hackathon.imagefinder.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.MalformedURLException;
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

    /**
     * Constructor for WebCrawler
     * 
     * @param url         The starting URL to crawl
     * @param maxPages    Maximum number of pages to crawl
     * @param threadCount Number of threads to use
     * @param crawlDelayMs Delay between requests in milliseconds (to be "friendly")
     */
    public WebCrawler(String url, int maxPages, int threadCount, int crawlDelayMs) {
        this.baseUrl = normalizeUrl(url);
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
                
                // Process the URL
                processSinglePage(url);
                
                // Apply crawl delay based on robots.txt or default
                int delay = robotsTxtParser.getCrawlDelay(crawlDelayMs);
                Thread.sleep(delay);
                
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
        String normalizedUrl = normalizeUrl(url);

        // Skip if already visited or if max pages reached
        if (visitedUrls.contains(normalizedUrl) || pagesCrawled.get() >= maxPages) {
            return;
        }

        // Check if URL is in the same domain
        if (!isSameDomain(normalizedUrl)) {
            return;
        }
        
        // Check robots.txt rules
        if (!robotsTxtParser.isAllowed(normalizedUrl)) {
            System.out.println("Skipping URL (disallowed by robots.txt): " + normalizedUrl);
            return;
        }

        // Mark as visited
        synchronized (lock) {
            if (visitedUrls.add(normalizedUrl)) {
                // Add to queue for processing
                urlQueue.add(normalizedUrl);
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
            // Connect to the URL and get the HTML document
            Document document = Jsoup.connect(url)
                    .userAgent("Eulerity-Crawler/1.0")
                    .timeout(10000)
                    .get();

            // Extract images
            extractImages(document, url);

            // Extract links for further crawling
            extractLinks(document);

        } catch (IOException e) {
            System.err.println("Error processing URL: " + url + " - " + e.getMessage());
        }
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
 * Add an image to the results with metadata
 * 
 * @param imageUrl The URL of the image
 * @param imgElement The img element (may be null for CSS background images)
 * @param pageUrl The URL of the page where the image was found
 */
private void addImage(String imageUrl, Element imgElement, String pageUrl) {
    // Only add if it's a new image
    if (!imageUrls.contains(imageUrl)) {
        imageUrls.add(imageUrl);
        
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
                if (!url.isEmpty() && (url.startsWith("http") || url.startsWith("/"))) {
                    // Convert relative URLs to absolute
                    if (url.startsWith("/")) {
                        url = domain + url;
                    }
                    addImage(url, null, pageUrl);
                }
            }
        }
    }
    
    /**
     * Check if a URL points to an image file
     * 
     * @param url The URL to check
     * @return true if it's an image URL, false otherwise
     */
    private boolean isImageUrl(String url) {
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
            // Skip image URLs as they've already been processed
            if (!isImageUrl(linkUrl)) {
                queueUrl(linkUrl);
            }
        }
        
        // Extract iframe sources
        Elements iframeElements = document.select("iframe[src]");
        for (Element iframe : iframeElements) {
            String srcUrl = iframe.absUrl("src");
            queueUrl(srcUrl);
        }
        
        // Extract form actions
        Elements formElements = document.select("form[action]");
        for (Element form : formElements) {
            String actionUrl = form.absUrl("action");
            queueUrl(actionUrl);
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
            String urlDomain = urlObj.getProtocol() + "://" + urlObj.getHost();
            return domain.equals(urlDomain);
        } catch (MalformedURLException e) {
            return false;
        }
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
            return urlObj.getProtocol() + "://" + urlObj.getHost();
        } catch (MalformedURLException e) {
            // If URL is malformed, return the original URL as a fallback
            return url;
        }
    }

    /**
     * Normalize URL by removing fragments and unnecessary parts
     * 
     * @param url The URL to normalize
     * @return Normalized URL
     */
    private String normalizeUrl(String url) {
        // Remove fragments
        int fragmentIndex = url.indexOf('#');
        if (fragmentIndex > 0) {
            url = url.substring(0, fragmentIndex);
        }
        
        // Ensure URL has protocol
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        
        // Remove trailing slash if present
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        
        return url;
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