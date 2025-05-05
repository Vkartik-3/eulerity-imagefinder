package com.eulerity.hackathon.imagefinder.crawler;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enhanced LogoDetector that uses various heuristics to identify logo images.
 * This implementation considers URL patterns, image dimensions, alt text, and context.
 */
public class LogoDetector {
    
    // Common logo-related terms in URLs and alt text
    private static final Set<String> LOGO_TERMS = new HashSet<>();
    static {
        LOGO_TERMS.add("logo");
        LOGO_TERMS.add("brand");
        LOGO_TERMS.add("icon");
        LOGO_TERMS.add("badge");
        LOGO_TERMS.add("symbol");
        LOGO_TERMS.add("emblem");
        LOGO_TERMS.add("trademark");
        LOGO_TERMS.add("logotype");
        LOGO_TERMS.add("identity");
        LOGO_TERMS.add("branding");
    }
    
    // File extensions commonly used for logos
    private static final Set<String> LOGO_EXTENSIONS = new HashSet<>();
    static {
        LOGO_EXTENSIONS.add(".ico");
        LOGO_EXTENSIONS.add(".svg");
        LOGO_EXTENSIONS.add(".png"); // Many logos use PNG for transparency
    }
    
    // Common logo path segments
    private static final Set<String> LOGO_PATH_SEGMENTS = new HashSet<>();
    static {
        LOGO_PATH_SEGMENTS.add("/img/logo");
        LOGO_PATH_SEGMENTS.add("/images/logo");
        LOGO_PATH_SEGMENTS.add("/assets/logo");
        LOGO_PATH_SEGMENTS.add("/static/logo");
        LOGO_PATH_SEGMENTS.add("/assets/brand");
        LOGO_PATH_SEGMENTS.add("/img/brand");
        LOGO_PATH_SEGMENTS.add("/images/brand");
        LOGO_PATH_SEGMENTS.add("/icons/");
        LOGO_PATH_SEGMENTS.add("/logos/");
    }
    
    // Common logo dimensions (width, height) in pixels
    private static final Set<Integer> COMMON_LOGO_SIZES = new HashSet<>();
    static {
        COMMON_LOGO_SIZES.add(16);  // favicon
        COMMON_LOGO_SIZES.add(32);  // favicon
        COMMON_LOGO_SIZES.add(48);  // touch icon
        COMMON_LOGO_SIZES.add(64);  // small logo
        COMMON_LOGO_SIZES.add(96);  // medium logo
        COMMON_LOGO_SIZES.add(128); // medium logo
        COMMON_LOGO_SIZES.add(192); // app icon
        COMMON_LOGO_SIZES.add(256); // large logo
    }
    
    // Debug flag - set to false in production
    private static final boolean DEBUG = false;
    
    /**
     * Main method to check if an image is likely to be a logo.
     * Combines all available heuristics.
     * 
     * @param imageUrl The URL of the image to check
     * @param width The width of the image, or -1 if unknown
     * @param height The height of the image, or -1 if unknown
     * @param altText The alt text of the image, or null if none
     * @param pageUrl The URL of the page where the image was found
     * @return true if the image is likely a logo, false otherwise
     */
    public static boolean isLikelyLogo(String imageUrl, int width, int height, String altText, String pageUrl) {
        // If URL is null or empty, it's not a logo
        if (imageUrl == null || imageUrl.isEmpty()) {
            return false;
        }
        
        // Canonicalize the URL to handle it consistently
        imageUrl = canonicalizeUrl(imageUrl);
        
        // Convert to lowercase for case-insensitive matching
        String lowerUrl = imageUrl.toLowerCase();
        
        // Track confidence score
        int confidence = 0;
        
        // Check URL patterns
        confidence += checkUrlPatterns(lowerUrl);
        
        // Check for site name in image URL
        confidence += checkSiteNameInUrl(pageUrl, imageUrl);
        
        // Check dimensions
        confidence += checkDimensions(width, height);
        
        // Check alt text
        confidence += checkAltText(altText);
        
        // Check page context
        confidence += checkPageContext(pageUrl, imageUrl);
        
        // Log the result if debugging is enabled
        if (DEBUG) {
            System.out.println("Logo detection for " + imageUrl + ": confidence = " + confidence);
        }
        
        // Return true if confidence is above threshold
        return confidence >= 2; // Require at least 2 positive indicators
    }
    
    /**
     * Check URL patterns that suggest a logo
     * 
     * @param url The lowercase URL to check
     * @return score increase if patterns found
     */
    private static int checkUrlPatterns(String url) {
        int score = 0;
        
        // Check common logo terms in URL
        for (String term : LOGO_TERMS) {
            if (url.contains(term)) {
                score++;
                if (DEBUG) System.out.println("Found logo term: " + term + " in " + url);
                break; // Count only once
            }
        }
        
        // Check file extension
        for (String ext : LOGO_EXTENSIONS) {
            if (url.endsWith(ext)) {
                score++;
                if (DEBUG) System.out.println("Found logo extension: " + ext + " in " + url);
                break; // Count only once
            }
        }
        
        // Check path segments
        for (String segment : LOGO_PATH_SEGMENTS) {
            if (url.contains(segment)) {
                score++;
                if (DEBUG) System.out.println("Found logo path segment: " + segment + " in " + url);
                break; // Count only once
            }
        }
        
        // Check for common logo filename patterns
        Pattern logoPattern = Pattern.compile(".*/((brand|logo|icon|symbol|badge)[-_]?([a-z0-9]+)?\\.(png|jpg|jpeg|gif|svg|ico|webp))$");
        if (logoPattern.matcher(url).matches()) {
            score += 2; // Higher confidence for explicit logo filenames
            if (DEBUG) System.out.println("Matched logo filename pattern in " + url);
        }
        
        return Math.min(score, 3); // Cap at 3 to prevent over-counting
    }
    
    /**
     * Check if site name appears in the image URL (common for logos)
     * 
     * @param pageUrl URL of the page where image was found
     * @param imageUrl URL of the image
     * @return score increase if site name is in image URL
     */
    private static int checkSiteNameInUrl(String pageUrl, String imageUrl) {
        if (pageUrl == null || imageUrl == null) {
            return 0;
        }
        
        try {
            // Extract domain from page URL
            URL pageUrlObj = new URL(pageUrl);
            String host = pageUrlObj.getHost().toLowerCase();
            
            // Remove common TLDs and subdomains
            String siteName = extractSiteName(host);
            
            // Check if site name is in image URL
            String lowerImageUrl = imageUrl.toLowerCase();
            
            if (siteName.length() > 3) { // Avoid short names with high false positive rate
                // Check if image URL contains site name adjacent to logo terms
                for (String term : LOGO_TERMS) {
                    if (lowerImageUrl.contains(siteName + "-" + term) || 
                        lowerImageUrl.contains(siteName + "_" + term) ||
                        lowerImageUrl.contains(term + "-" + siteName) ||
                        lowerImageUrl.contains(term + "_" + siteName) ||
                        lowerImageUrl.contains(siteName + term) ||
                        lowerImageUrl.contains(term + siteName)) {
                        
                        if (DEBUG) System.out.println("Found site name '" + siteName + "' with logo term in image URL");
                        return 3; // Strong indicator
                    }
                }
                
                // Check if image URL contains site name and common logo indicators
                if ((lowerImageUrl.contains(siteName) && (lowerImageUrl.contains("header") || 
                     lowerImageUrl.contains("footer") || lowerImageUrl.contains("navbar") ||
                     lowerImageUrl.endsWith(".svg") || lowerImageUrl.endsWith(".ico")))) {
                    
                    if (DEBUG) System.out.println("Found site name '" + siteName + "' with logo indicator in image URL");
                    return 2;
                }
            }
        } catch (MalformedURLException e) {
            // Ignore parsing errors
        }
        
        return 0;
    }
    
    /**
     * Extract site name from hostname
     * 
     * @param host Hostname (e.g., www.example.com)
     * @return Site name (e.g., example)
     */
    private static String extractSiteName(String host) {
        // Remove www. prefix if present
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        
        // Remove common TLDs
        int lastDot = host.lastIndexOf('.');
        if (lastDot > 0) {
            host = host.substring(0, lastDot);
        }
        
        // Handle multi-part domains (e.g., co.uk)
        lastDot = host.lastIndexOf('.');
        if (lastDot > 0) {
            String potentialTld = host.substring(lastDot + 1);
            if (potentialTld.length() <= 3) { // Common country codes are 2-3 chars
                host = host.substring(0, lastDot);
            }
        }
        
        return host;
    }
    
    /**
     * Check image dimensions for logo-like characteristics
     * 
     * @param width Image width
     * @param height Image height
     * @return score increase if dimensions suggest a logo
     */
    private static int checkDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 0; // Unknown dimensions
        }
        
        int score = 0;
        
        // Check for square or nearly square aspect ratio (common for logos)
        double ratio = Math.max(width, height) / (double) Math.min(width, height);
        if (ratio <= 1.5) {
            score++;
            if (DEBUG) System.out.println("Image has logo-like aspect ratio: " + ratio);
        }
        
        // Check for common logo sizes
        if (COMMON_LOGO_SIZES.contains(width) || COMMON_LOGO_SIZES.contains(height)) {
            score++;
            if (DEBUG) System.out.println("Image has common logo dimension: " + width + "x" + height);
        }
        
        // Check for small image (logos tend to be smaller)
        if (width < 300 && height < 300) {
            score++;
            if (DEBUG) System.out.println("Image has small dimensions: " + width + "x" + height);
        }
        
        return Math.min(score, 2); // Cap at 2
    }
    
    /**
     * Check alt text for logo-like descriptions
     * 
     * @param altText The alt text to check
     * @return score increase if alt text suggests a logo
     */
    private static int checkAltText(String altText) {
        if (altText == null || altText.isEmpty()) {
            return 0;
        }
        
        String lowerAlt = altText.toLowerCase();
        
        // Check for logo terms in alt text
        for (String term : LOGO_TERMS) {
            if (lowerAlt.contains(term)) {
                if (DEBUG) System.out.println("Found logo term in alt text: " + term);
                return 2; // Strong indicator
            }
        }
        
        // Check for company name followed by "logo"
        Pattern companyLogoPattern = Pattern.compile(".*\\b([a-z0-9]+ logo)\\b.*");
        if (companyLogoPattern.matcher(lowerAlt).matches()) {
            if (DEBUG) System.out.println("Found company logo pattern in alt text: " + lowerAlt);
            return 3; // Very strong indicator
        }
        
        return 0;
    }
    
    /**
     * Check page context for indications this is a logo
     * 
     * @param pageUrl The URL of the page
     * @param imageUrl The URL of the image
     * @return score increase based on page context
     */
    private static int checkPageContext(String pageUrl, String imageUrl) {
        if (pageUrl == null || pageUrl.isEmpty()) {
            return 0;
        }
        
        int score = 0;
        
        // Check if image is found in header or footer paths
        if (imageUrl.contains("/header/") || imageUrl.contains("/footer/")) {
            score++;
            if (DEBUG) System.out.println("Image found in header/footer path");
        }
        
        // Check if in common site sections for logos
        if (pageUrl.contains("/about") || pageUrl.contains("/contact") || 
            pageUrl.contains("/home") || pageUrl.contains("/index")) {
            score++;
            if (DEBUG) System.out.println("Image found on main site page");
        }
        
        // Check if image is small and in top area of page (based on img selector if available)
        // This would require DOM position information we don't currently have
        
        return score;
    }
    
    /**
     * Canonicalize URL for consistent handling
     * 
     * @param url The URL to canonicalize
     * @return Canonicalized URL
     */
    private static String canonicalizeUrl(String url) {
        // Remove URL fragments
        int fragmentIndex = url.indexOf('#');
        if (fragmentIndex > 0) {
            url = url.substring(0, fragmentIndex);
        }
        
        // Ensure protocol
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        
        return url;
    }
    
    /**
     * Legacy method for backward compatibility
     */
    public static boolean isLikelyLogo(String imageUrl) {
        return isLikelyLogo(imageUrl, -1, -1, null, null);
    }
    
    /**
     * Legacy method for backward compatibility
     */
    public static boolean isLikelyLogo(String imageUrl, int width, int height, String altText) {
        return isLikelyLogo(imageUrl, width, height, altText, null);
    }
}