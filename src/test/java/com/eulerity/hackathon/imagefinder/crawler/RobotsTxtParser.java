package com.eulerity.hackathon.imagefinder.crawler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for robots.txt files to ensure the crawler is "friendly"
 * and respects the website's crawling policies.
 */
public class RobotsTxtParser {
    private static final String USER_AGENT = "Eulerity-Crawler";
    private static final String WILDCARD_USER_AGENT = "*";
    
    private final String domain;
    private final Map<String, List<String>> disallowedPaths;
    private final Map<String, List<String>> allowedPaths;
    private final Map<String, Integer> crawlDelays;
    private boolean fetchFailed = false;
    
    /**
     * Constructor - fetches and parses robots.txt for the given domain
     * 
     * @param domain The domain to parse robots.txt for
     */
    public RobotsTxtParser(String domain) {
        this.domain = domain;
        this.disallowedPaths = new HashMap<>();
        this.allowedPaths = new HashMap<>();
        this.crawlDelays = new HashMap<>();
        
        try {
            fetchAndParseRobotsTxt();
        } catch (IOException e) {
            // If robots.txt doesn't exist or can't be fetched, assume everything is allowed
            System.out.println("Could not fetch robots.txt: " + e.getMessage());
            fetchFailed = true;
        }
    }
    
    /**
     * Fetch and parse the robots.txt file
     * 
     * @throws IOException If the robots.txt file can't be fetched
     */
    private void fetchAndParseRobotsTxt() throws IOException {
        URL robotsUrl = new URL(domain + "/robots.txt");
        HttpURLConnection connection = (HttpURLConnection) robotsUrl.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        
        int responseCode = connection.getResponseCode();
        
        // If robots.txt doesn't exist (404) or other error, assume everything is allowed
        if (responseCode != HttpURLConnection.HTTP_OK) {
            fetchFailed = true;
            return;
        }
        
        // Read and parse the robots.txt file
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String line;
        String currentUserAgent = null;
        
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            
            // Skip empty lines and comments
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            // Check for User-agent line
            if (line.toLowerCase().startsWith("user-agent:")) {
                String userAgent = line.substring("user-agent:".length()).trim();
                currentUserAgent = userAgent;
                
                // Initialize collections for this user agent if needed
                if (!disallowedPaths.containsKey(currentUserAgent)) {
                    disallowedPaths.put(currentUserAgent, new ArrayList<>());
                    allowedPaths.put(currentUserAgent, new ArrayList<>());
                }
                continue;
            }
            
            // Skip lines if no user agent has been defined yet
            if (currentUserAgent == null) {
                continue;
            }
            
            // Check for Disallow directive
            if (line.toLowerCase().startsWith("disallow:")) {
                String path = line.substring("disallow:".length()).trim();
                if (!path.isEmpty()) {
                    disallowedPaths.get(currentUserAgent).add(path);
                }
                continue;
            }
            
            // Check for Allow directive
            if (line.toLowerCase().startsWith("allow:")) {
                String path = line.substring("allow:".length()).trim();
                if (!path.isEmpty()) {
                    allowedPaths.get(currentUserAgent).add(path);
                }
                continue;
            }
            
            // Check for Crawl-delay directive
            if (line.toLowerCase().startsWith("crawl-delay:")) {
                try {
                    int delay = Integer.parseInt(line.substring("crawl-delay:".length()).trim());
                    crawlDelays.put(currentUserAgent, delay * 1000); // Convert to milliseconds
                } catch (NumberFormatException e) {
                    // Ignore invalid crawl delays
                }
            }
        }
        
        reader.close();
    }
    
    /**
     * Check if a URL is allowed to be crawled according to robots.txt rules
     * 
     * @param url The URL to check
     * @return true if the URL is allowed, false otherwise
     */
    public boolean isAllowed(String url) {
        // If robots.txt couldn't be fetched, assume everything is allowed
        if (fetchFailed) {
            return true;
        }
        
        try {
            URL urlObj = new URL(url);
            String path = urlObj.getPath();
            
            // First check specific user agent rules
            if (isPathAllowed(path, USER_AGENT)) {
                return true;
            }
            
            // Then check wildcard rules
            return isPathAllowed(path, WILDCARD_USER_AGENT);
            
        } catch (MalformedURLException e) {
            return false;
        }
    }
    
    /**
     * Check if a path is allowed for a specific user agent
     * 
     * @param path The path to check
     * @param userAgent The user agent to check for
     * @return true if allowed, false otherwise
     */
    private boolean isPathAllowed(String path, String userAgent) {
        // If no rules for this user agent, it's allowed
        if (!disallowedPaths.containsKey(userAgent)) {
            return true;
        }
        
        // Check allow rules first (they take precedence over disallow)
        for (String allowPath : allowedPaths.getOrDefault(userAgent, new ArrayList<>())) {
            if (pathMatches(path, allowPath)) {
                return true;
            }
        }
        
        // Then check disallow rules
        for (String disallowPath : disallowedPaths.getOrDefault(userAgent, new ArrayList<>())) {
            if (pathMatches(path, disallowPath)) {
                return false;
            }
        }
        
        // If no rules matched, it's allowed
        return true;
    }
    
    /**
     * Check if a path matches a robots.txt path pattern
     * 
     * @param path The path to check
     * @param pattern The pattern from robots.txt
     * @return true if matches, false otherwise
     */
    private boolean pathMatches(String path, String pattern) {
        // Convert robots.txt pattern to regex
        String regex = pattern
                .replace(".", "\\.")
                .replace("?", "\\?")
                .replace("*", ".*")
                .replace("$", "\\$");
        
        // Ensure the pattern matches the entire path
        if (!pattern.endsWith("$")) {
            regex = "^" + regex;
        }
        
        // Check if path matches the pattern
        Pattern compiledPattern = Pattern.compile(regex);
        Matcher matcher = compiledPattern.matcher(path);
        return matcher.find();
    }
    
    /**
     * Get the recommended crawl delay for a user agent
     * 
     * @param userAgent The user agent to get the delay for
     * @param defaultDelay The default delay to use if not specified
     * @return The crawl delay in milliseconds
     */
    public int getCrawlDelay(String userAgent, int defaultDelay) {
        // Check for specific user agent delay
        if (crawlDelays.containsKey(userAgent)) {
            return crawlDelays.get(userAgent);
        }
        
        // Check for wildcard delay
        if (crawlDelays.containsKey(WILDCARD_USER_AGENT)) {
            return crawlDelays.get(WILDCARD_USER_AGENT);
        }
        
        // If no delay specified, return default
        return defaultDelay;
    }
    
    /**
     * Get the recommended crawl delay for the Eulerity-Crawler
     * 
     * @param defaultDelay The default delay to use if not specified
     * @return The crawl delay in milliseconds
     */
    public int getCrawlDelay(int defaultDelay) {
        return getCrawlDelay(USER_AGENT, defaultDelay);
    }
}