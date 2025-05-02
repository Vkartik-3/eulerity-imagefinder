package com.eulerity.hackathon.imagefinder.crawler;

/**
 * Class to store metadata about an image found during crawling.
 */
public class ImageMetadata {
    private final String url;
    private boolean isLogo;
    private String altText;
    private int width;
    private int height;
    private String pageFound;
    
    /**
     * Constructor for ImageMetadata
     * 
     * @param url The URL of the image
     */
    public ImageMetadata(String url) {
        this.url = url;
        this.isLogo = false;
        this.width = -1;
        this.height = -1;
    }
    
    /**
     * Get the URL of the image
     * 
     * @return The image URL
     */
    public String getUrl() {
        return url;
    }
    
    /**
     * Check if the image is flagged as a logo
     * 
     * @return true if the image is a logo, false otherwise
     */
    public boolean isLogo() {
        return isLogo;
    }
    
    /**
     * Set whether the image is a logo
     * 
     * @param isLogo true if the image is a logo, false otherwise
     */
    public void setLogo(boolean isLogo) {
        this.isLogo = isLogo;
    }
    
    /**
     * Get the alt text of the image
     * 
     * @return The alt text
     */
    public String getAltText() {
        return altText;
    }
    
    /**
     * Set the alt text of the image
     * 
     * @param altText The alt text
     */
    public void setAltText(String altText) {
        this.altText = altText;
    }
    
    /**
     * Get the width of the image
     * 
     * @return The width in pixels, or -1 if unknown
     */
    public int getWidth() {
        return width;
    }
    
    /**
     * Set the width of the image
     * 
     * @param width The width in pixels
     */
    public void setWidth(int width) {
        this.width = width;
    }
    
    /**
     * Get the height of the image
     * 
     * @return The height in pixels, or -1 if unknown
     */
    public int getHeight() {
        return height;
    }
    
    /**
     * Set the height of the image
     * 
     * @param height The height in pixels
     */
    public void setHeight(int height) {
        this.height = height;
    }
    
    /**
     * Get the URL of the page where the image was found
     * 
     * @return The page URL
     */
    public String getPageFound() {
        return pageFound;
    }
    
    /**
     * Set the URL of the page where the image was found
     * 
     * @param pageFound The page URL
     */
    public void setPageFound(String pageFound) {
        this.pageFound = pageFound;
    }
}