package com.eulerity.hackathon.imagefinder.crawler;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility class for detecting if an image is likely to be a logo.
 * Uses heuristics based on image filename, path, size, and context.
 */
public class LogoDetector {

    // Common patterns in logo image URLs
    private static final Set<String> LOGO_KEYWORDS = new HashSet<>(Arrays.asList(
            "logo", "brand", "icon", "symbol", "emblem", "favicon", "badge"
    ));
    
    // Common file patterns for logos
    private static final Pattern LOGO_PATTERN = Pattern.compile(
            ".*?(logo|brand|header|nav|icon).*?\\.(png|svg|jpg|jpeg|gif|webp)", 
            Pattern.CASE_INSENSITIVE);
    
    /**
     * Checks if an image URL is likely to be a logo based on its path and other heuristics.
     * 
     * @param imageUrl The image URL to check
     * @param imageWidth Width of the image if available, -1 otherwise
     * @param imageHeight Height of the image if available, -1 otherwise
     * @param altText Alt text of the image if available, null otherwise
     * @return true if the image is likely a logo, false otherwise
     */
    public static boolean isLikelyLogo(String imageUrl, int imageWidth, int imageHeight, String altText) {
        // Check if the image URL contains logo-related keywords
        if (LOGO_PATTERN.matcher(imageUrl).matches()) {
            return true;
        }
        
        try {
            URL url = new URL(imageUrl);
            String path = url.getPath().toLowerCase();
            
            // Check for standard logo paths
            if (path.contains("/logo/") || path.contains("/logos/") || path.contains("/brand/")) {
                return true;
            }
            
            // Get the filename
            String filename = path.substring(path.lastIndexOf('/') + 1);
            
            // Check if filename contains logo keywords
            for (String keyword : LOGO_KEYWORDS) {
                if (filename.contains(keyword)) {
                    return true;
                }
            }
            
            // Check if it's a favicon
            if (path.contains("favicon")) {
                return true;
            }
            
            // Check image dimensions if available
            if (imageWidth > 0 && imageHeight > 0) {
                // Logos are often square or close to square
                double ratio = (double) imageWidth / imageHeight;
                if (ratio >= 0.7 && ratio <= 1.3) {
                    // Small square images are often logos
                    if (imageWidth <= 300 && imageHeight <= 300) {
                        return true;
                    }
                }
            }
            
            // Check alt text if available
            if (altText != null && !altText.isEmpty()) {
                altText = altText.toLowerCase();
                for (String keyword : LOGO_KEYWORDS) {
                    if (altText.contains(keyword)) {
                        return true;
                    }
                }
                
                // "Company name" or "website name" is often used for logos
                if (altText.contains("company") || altText.contains("website")) {
                    return true;
                }
            }
            
        } catch (Exception e) {
            // If there's an error parsing the URL, return false
            return false;
        }
        
        return false;
    }
    
    /**
     * Simplified version of the logo detection that only uses the URL
     * 
     * @param imageUrl The image URL to check
     * @return true if the image is likely a logo, false otherwise
     */
    public static boolean isLikelyLogo(String imageUrl) {
        return isLikelyLogo(imageUrl, -1, -1, null);
    }
}