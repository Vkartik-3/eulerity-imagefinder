package com.eulerity.hackathon.imagefinder.crawler;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;

/**
 * Enhanced utility class for detecting if an image is likely to be a logo.
 * Uses advanced heuristics based on image filename, path, size, context, and domain-specific patterns.
 */
public class LogoDetector {

    // Common patterns in logo image URLs
    private static final Set<String> LOGO_KEYWORDS = new HashSet<>(Arrays.asList(
            "logo", "brand", "icon", "symbol", "emblem", "favicon", "badge",
            "mark", "identity", "logotype", "sign", "trademark"
    ));
    
    // Product and app icon keywords
    private static final Set<String> PRODUCT_ICON_KEYWORDS = new HashSet<>(Arrays.asList(
            "app", "product", "icon", "button", "tool", "feature", "service"
    ));
    
    // Common file patterns for logos
    private static final Pattern LOGO_PATTERN = Pattern.compile(
            ".*?(logo|brand|header|nav|icon|badge|identity|symbol).*?\\.(png|svg|jpg|jpeg|gif|webp)",
            Pattern.CASE_INSENSITIVE);
    
    // Common path patterns for logos
    private static final Pattern LOGO_PATH_PATTERN = Pattern.compile(
            ".*((\\/|\\\\)(logo|logos|brand|branding|icon|icons|symbol|symbols|badge|badges)(\\/|\\\\)).*",
            Pattern.CASE_INSENSITIVE);
    
    // Domain-specific logo patterns
    private static final Map<String, Pattern> DOMAIN_SPECIFIC_PATTERNS = new HashMap<>();
    
    static {
        // NASA specific patterns
        DOMAIN_SPECIFIC_PATTERNS.put("nasa.gov", Pattern.compile(
                ".*?(nasa|space|mission|shuttle|rocket|station|exploration|science).*?(logo|icon|symbol|badge).*",
                Pattern.CASE_INSENSITIVE));
        
        // Adobe specific patterns
        DOMAIN_SPECIFIC_PATTERNS.put("adobe.com", Pattern.compile(
                ".*?(adobe|photoshop|illustrator|indesign|premiere|lightroom|acrobat|aftereffects|xd|cc).*?(logo|icon|product|app).*",
                Pattern.CASE_INSENSITIVE));
        
        // Generic product icon patterns
        DOMAIN_SPECIFIC_PATTERNS.put("generic", Pattern.compile(
                ".*?(product|app|tool|feature|function).*?(icon|logo|button).*",
                Pattern.CASE_INSENSITIVE));
    }
    
    /**
     * Checks if an image URL is likely to be a logo based on its path and other heuristics.
     * Enhanced to detect domain-specific patterns and product icons.
     * 
     * @param imageUrl The image URL to check
     * @param imageWidth Width of the image if available, -1 otherwise
     * @param imageHeight Height of the image if available, -1 otherwise
     * @param altText Alt text of the image if available, null otherwise
     * @param pageUrl URL of the page where the image was found, if available
     * @return true if the image is likely a logo, false otherwise
     */
    public static boolean isLikelyLogo(String imageUrl, int imageWidth, int imageHeight, String altText, String pageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return false;
        }
        
        // Check if the image URL matches common logo patterns
        if (LOGO_PATTERN.matcher(imageUrl).matches()) {
            return true;
        }
        
        try {
            URL url = new URL(imageUrl);
            String path = url.getPath().toLowerCase();
            String host = url.getHost().toLowerCase();
            
            // Check path patterns that typically contain logos
            if (LOGO_PATH_PATTERN.matcher(path).matches()) {
                return true;
            }
            
            // Check domain-specific patterns
            for (Map.Entry<String, Pattern> entry : DOMAIN_SPECIFIC_PATTERNS.entrySet()) {
                if (host.contains(entry.getKey()) || entry.getKey().equals("generic")) {
                    if (entry.getValue().matcher(path).matches()) {
                        return true;
                    }
                }
            }
            
            // Get the filename
            String filename = path.substring(path.lastIndexOf('/') + 1);
            
            // Check if filename contains logo keywords
            for (String keyword : LOGO_KEYWORDS) {
                if (filename.toLowerCase().contains(keyword)) {
                    return true;
                }
            }
            
            // Check for product icon keywords
            for (String keyword : PRODUCT_ICON_KEYWORDS) {
                if (filename.toLowerCase().contains(keyword)) {
                    return true;
                }
            }
            
            // Check if it's a favicon
            if (path.contains("favicon") || filename.startsWith("favicon")) {
                return true;
            }
            
            // Check image dimensions if available
            if (imageWidth > 0 && imageHeight > 0) {
                // Small square images are often icons/logos
                if (imageWidth <= 256 && imageHeight <= 256) {
                    double ratio = (double) imageWidth / imageHeight;
                    if (ratio >= 0.8 && ratio <= 1.2) { // Nearly square
                        return true;
                    }
                }
                
                // Very small images are likely icons
                if (imageWidth <= 64 && imageHeight <= 64) {
                    return true;
                }
            }
            
            // Check alt text if available
            if (altText != null && !altText.isEmpty()) {
                altText = altText.toLowerCase();
                
                // Check for logo keywords in alt text
                for (String keyword : LOGO_KEYWORDS) {
                    if (altText.contains(keyword)) {
                        return true;
                    }
                }
                
                // Check for product keywords in alt text
                for (String keyword : PRODUCT_ICON_KEYWORDS) {
                    if (altText.contains(keyword)) {
                        return true;
                    }
                }
                
                // Check for company name patterns in alt text
                if (altText.contains("logo") || 
                    altText.contains("brand") || 
                    altText.contains("company") || 
                    altText.contains("website") ||
                    altText.contains("icon")) {
                    return true;
                }
                
                // Check for social media icon patterns
                if (altText.contains("facebook") || 
                    altText.contains("twitter") || 
                    altText.contains("instagram") ||
                    altText.contains("linkedin") ||
                    altText.contains("youtube") ||
                    altText.contains("social")) {
                    return true;
                }
                
                // Check for NASA specific terms in alt text
                if (host.contains("nasa.gov") && 
                   (altText.contains("nasa") || 
                    altText.contains("space") || 
                    altText.contains("mission") ||
                    altText.contains("agency"))) {
                    return true;
                }
                
                // Check for Adobe specific terms in alt text
                if (host.contains("adobe.com") && 
                   (altText.contains("adobe") || 
                    altText.contains("creative cloud") || 
                    altText.contains("cc") ||
                    altText.contains("photoshop") ||
                    altText.contains("illustrator"))) {
                    return true;
                }
            }
            
            // Additional checks based on the page URL
            if (pageUrl != null && !pageUrl.isEmpty()) {
                // If image is on an about page, more likely to be a logo
                if (pageUrl.contains("/about") || 
                    pageUrl.contains("/company") || 
                    pageUrl.contains("/brand")) {
                    // Small images on about pages are often logos
                    if (imageWidth > 0 && imageWidth <= 300 && 
                        imageHeight > 0 && imageHeight <= 200) {
                        return true;
                    }
                }
            }
            
        } catch (Exception e) {
            // If there's an error parsing the URL, return false
            return false;
        }
        
        return false;
    }
    
    /**
     * Overloaded version of isLikelyLogo that doesn't use page URL
     */
    public static boolean isLikelyLogo(String imageUrl, int imageWidth, int imageHeight, String altText) {
        return isLikelyLogo(imageUrl, imageWidth, imageHeight, altText, null);
    }
    
    /**
     * Simplified version of the logo detection that only uses the URL
     * 
     * @param imageUrl The image URL to check
     * @return true if the image is likely a logo, false otherwise
     */
    public static boolean isLikelyLogo(String imageUrl) {
        return isLikelyLogo(imageUrl, -1, -1, null, null);
    }
}