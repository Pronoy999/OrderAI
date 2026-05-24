package com.orderai;

import com.orderai.config.Config;
import com.orderai.gemini.GeminiClient;
import com.orderai.gmail.GmailClient;
import com.orderai.model.Order;
import com.orderai.model.OrderItem;
import com.orderai.sheets.SheetsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        logger.info("Starting OrderAI Application Daemon...");

        try {
            // Load and validate configuration
            Config config = Config.getInstance();
            config.logConfigSummary();

            // Initialize clients
            GmailClient gmailClient = new GmailClient(config.getGmailEmail(), config.getGmailAppPassword());
            GeminiClient geminiClient = new GeminiClient(config.getGeminiApiKey());
            
            SheetsClient sheetsClient = null;
            if (!config.isDryRun()) {
                sheetsClient = new SheetsClient(config.getSpreadsheetId(), config.getGoogleCredentialsJson());
            } else {
                logger.info("DRY-RUN MODE ENABLED. Google Sheets updates will be skipped.");
            }

            final SheetsClient finalSheetsClient = sheetsClient;

            Runnable pollingTask = () -> {
                logger.info("Executing periodic Gmail polling cycle...");
                try {
                    // Fetch emails from the last 7 days
                    List<GmailClient.EmailMessage> emails = gmailClient.fetchBigBasketEmails(7);
                    logger.info("Fetched {} BigBasket emails to evaluate.", emails.size());

                    int newOrdersProcessed = 0;
                    for (GmailClient.EmailMessage email : emails) {
                        try {
                            logger.info("--------------------------------------------------");
                            logger.info("Processing Order Email: '{}' received on {}", email.getSubject(), email.getReceivedDate());

                            // 1. Call Gemini to parse and categorize items
                            Order parsedOrder = geminiClient.parseBigBasketEmail(email.getSubject(), email.getBody());
                            if (parsedOrder == null) {
                                logger.warn("Failed to parse order from email. Skipping.");
                                continue;
                            }

                            logger.info("Gemini parsed Order: ID={}, Date={}, ItemsCount={}", 
                                    parsedOrder.getOrderId(), parsedOrder.getOrderDate(), parsedOrder.getItems().size());

                            // 2. Perform actions depending on run mode
                            if (config.isDryRun()) {
                                logger.info("[DRY RUN] Order ID: {}", parsedOrder.getOrderId());
                                logger.info("[DRY RUN] Date: {}", parsedOrder.getOrderDate());
                                for (OrderItem item : parsedOrder.getItems()) {
                                    logger.info("[DRY RUN] Item: {} | Qty: {} | Price: INR {} | Category: {}", 
                                            item.getName(), item.getQuantity(), item.getPrice(), item.getCategory());
                                }
                            } else {
                                // Double-check duplication in the Google Sheet
                                if (finalSheetsClient.isOrderAlreadyLogged(parsedOrder.getOrderId())) {
                                    logger.info("Order ID: {} has ALREADY been logged in Google Sheets. Skipping duplicate.", parsedOrder.getOrderId());
                                    continue;
                                }

                                // Append items to Google Sheets
                                finalSheetsClient.logOrder(parsedOrder);
                                newOrdersProcessed++;
                            }
                        } catch (Exception ex) {
                            logger.error("Error processing email: {}", email.getSubject(), ex);
                        }
                    }

                    logger.info("Gmail polling cycle completed. Processed {} new orders.", newOrdersProcessed);
                    logger.info("--------------------------------------------------");

                } catch (Exception e) {
                    logger.error("Unhandled error during polling execution cycle", e);
                }
            };

            // Execution path based on configuration
            if (config.isDryRun()) {
                logger.info("Executing a single dry-run cycle and exiting...");
                pollingTask.run();
                logger.info("Dry-run cycle completed successfully. Terminating.");
                System.exit(0);
            } else {
                // Running in Daemon mode
                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                int interval = config.getPollIntervalMinutes();
                
                logger.info("Scheduling daemon loop to run every {} minutes...", interval);
                scheduler.scheduleAtFixedRate(pollingTask, 0, interval, TimeUnit.MINUTES);

                // Setup graceful shutdown hook
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    logger.info("Shutdown signal received. Stopping scheduler...");
                    scheduler.shutdown();
                    try {
                        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                            scheduler.shutdownNow();
                        }
                        logger.info("Scheduler terminated successfully. Goodbye.");
                    } catch (InterruptedException e) {
                        scheduler.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }));
            }

        } catch (Exception e) {
            logger.error("Critical failure during application startup. Shutting down.", e);
            System.exit(1);
        }
    }
}
