package com.orderai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.IOException;
import java.util.Properties;

public class Config {
    private static final Logger logger = LoggerFactory.getLogger(Config.class);

    private final String gmailEmail;
    private final String gmailAppPassword;
    private final String geminiApiKey;
    private final String spreadsheetId;
    private final String googleCredentialsJson;
    private final int pollIntervalMinutes;
    private final boolean dryRun;

    private static Config instance;

    private Config() {
        Properties props = new Properties();
        try (InputStream input = Config.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                props.load(input);
                logger.info("Loaded configurations from application.properties successfully.");
            } else {
                logger.warn("application.properties file not found in classpath. Falling back exclusively to env variables.");
            }
        } catch (IOException ex) {
            logger.error("Error reading application.properties from classpath", ex);
        }

        // Retrieve and prioritize env overrides over properties
        // Retrieve, prioritize env overrides, and aggressively sanitize whitespace/casing
        this.gmailEmail = getRequiredSetting("GMAIL_EMAIL", "gmail.email", props).toLowerCase().trim();
        this.gmailAppPassword = getRequiredSetting("GMAIL_APP_PASSWORD", "gmail.app.password", props).trim();
        this.geminiApiKey = getRequiredSetting("GEMINI_API_KEY", "gemini.api.key", props).trim();
        this.spreadsheetId = getRequiredSetting("SPREADSHEET_ID", "spreadsheet.id", props).trim();
        this.googleCredentialsJson = getRequiredSetting("GOOGLE_CREDENTIALS_JSON", "google.credentials.json", props).trim();

        this.pollIntervalMinutes = getOptionalSettingInt("POLL_INTERVAL_MINUTES", "poll.interval.minutes", 30, props);
        this.dryRun = getOptionalSettingBoolean("DRY_RUN", "dry.run", false, props);
    }

    public static synchronized Config getInstance() {
        if (instance == null) {
            instance = new Config();
        }
        return instance;
    }

    private String getRequiredSetting(String envName, String propName, Properties props) {
        // Priority 1: System Environment Variable
        String value = System.getenv(envName);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }

        // Priority 2: application.properties
        value = props.getProperty(propName);
        if (value != null && !value.trim().isEmpty() && !value.startsWith("YOUR_")) {
            return value.trim();
        }

        logger.error("Missing mandatory config: Environment variable '{}' or property '{}' is not configured.", envName, propName);
        throw new IllegalStateException("Missing mandatory configuration: " + envName + " / " + propName);
    }

    private int getOptionalSettingInt(String envName, String propName, int defaultValue, Properties props) {
        String value = System.getenv(envName);
        if (value == null || value.trim().isEmpty()) {
            value = props.getProperty(propName);
        }

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value for configuration {} / {}, using default: {}", envName, propName, defaultValue);
            return defaultValue;
        }
    }

    private boolean getOptionalSettingBoolean(String envName, String propName, boolean defaultValue, Properties props) {
        String value = System.getenv(envName);
        if (value == null || value.trim().isEmpty()) {
            value = props.getProperty(propName);
        }

        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value.trim());
    }

    public String getGmailEmail() {
        return gmailEmail;
    }

    public String getGmailAppPassword() {
        return gmailAppPassword;
    }

    public String getGeminiApiKey() {
        return geminiApiKey;
    }

    public String getSpreadsheetId() {
        return spreadsheetId;
    }

    public String getGoogleCredentialsJson() {
        return googleCredentialsJson;
    }

    public int getPollIntervalMinutes() {
        return pollIntervalMinutes;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void logConfigSummary() {
        logger.info("========================================");
        logger.info("OrderAI Config Loaded Successfully");
        logger.info("Gmail Email: {}", maskString(gmailEmail));
        logger.info("Spreadsheet ID: {}", maskString(spreadsheetId));
        logger.info("Poll Interval: {} minutes", pollIntervalMinutes);
        logger.info("Dry Run Mode: {}", dryRun);
        logger.info("========================================");
    }

    private String maskString(String str) {
        if (str == null || str.length() <= 4) {
            return "****";
        }
        return str.substring(0, 4) + "..." + str.substring(str.length() - 4);
    }
}
