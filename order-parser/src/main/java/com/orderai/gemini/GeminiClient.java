package com.orderai.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orderai.model.Order;
import com.orderai.model.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class GeminiClient {
    private static final Logger logger = LoggerFactory.getLogger(GeminiClient.class);

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GeminiClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public Order parseBigBasketEmail(String emailSubject, String emailBody) {
        logger.info("Sending email content to Gemini API for parsing & categorization...");
        try {
            // 1. Clean the HTML down to text to prevent HttpTimeoutExceptions
            String cleanBody = cleanHtml(emailBody);
            String prompt = constructPrompt(emailSubject, cleanBody);

            // Construct JSON request body for Gemini API
            ObjectNode rootNode = objectMapper.createObjectNode();
            ArrayNode contentsArray = rootNode.putArray("contents");
            ObjectNode contentNode = contentsArray.addObject();
            ArrayNode partsArray = contentNode.putArray("parts");
            partsArray.addObject().put("text", prompt);

            // 2. Enforce Strict Server-Side JSON Schema
            ObjectNode genConfig = rootNode.putObject("generationConfig");
            genConfig.put("responseMimeType", "application/json");
            genConfig.set("responseSchema", createOrderResponseSchema());

            String requestBody = objectMapper.writeValueAsString(rootNode);

            // Update URL to a fast, stable release model
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(45)) // Safe processing buffer
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.error("Gemini API returned error status: {}. Response: {}", response.statusCode(), response.body());
                return null;
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            JsonNode candidates = responseJson.path("candidates");
            if (candidates.isMissingNode() || candidates.size() == 0) {
                logger.error("No completion candidates returned by Gemini.");
                return null;
            }

            String jsonText = candidates.get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            logger.info("Successfully received response from Gemini API.");

            // Parse guaranteed valid schema structure
            JsonNode orderJson = objectMapper.readTree(jsonText);
            String orderId = orderJson.path("orderId").asText(null);
            String orderDate = orderJson.path("orderDate").asText(null);

            if (orderId == null || orderId.trim().isEmpty() || "null".equalsIgnoreCase(orderId)) {
                logger.warn("Gemini could not locate orderId. Defaulting to subject hash.");
                orderId = "BB-HASH-" + Math.abs(emailSubject.hashCode());
            }

            JsonNode itemsArray = orderJson.path("items");
            List<OrderItem> items = new ArrayList<>();
            if (itemsArray.isArray()) {
                for (JsonNode itemNode : itemsArray) {
                    String name = itemNode.path("name").asText("Unknown Item");
                    String quantity = itemNode.path("quantity").asText("1 unit");
                    double price = itemNode.path("price").asDouble(0.0);
                    String category = itemNode.path("category").asText("Other");

                    items.add(new OrderItem(name, quantity, price, category));
                }
            }

            return new Order(orderId, orderDate, items);

        } catch (java.net.http.HttpTimeoutException e) {
            logger.error("Request timed out! The email size might still be too large or network link choked.", e);
            return null;
        } catch (Exception e) {
            logger.error("Failed to call or parse Gemini API response", e);
            return null;
        }
    }

    /**
     * Aggressively strips out style definitions, script macros, and HTML structural layout junk.
     * Drops string context size down by up to 90%, preventing timeouts.
     */
    private String cleanHtml(String html) {
        if (html == null) return "";
        String text = html;
        text = text.replaceAll("(?s)<style>.*?</style>", "");
        text = text.replaceAll("(?s)<script>.*?</script>", "");
        text = text.replaceAll("<[^>]*>", " ");
        text = text.replaceAll("\\s+", " ").trim();

        // Hard-cap the text length at 12,000 characters to keep payload safe
        if (text.length() > 12000) {
            text = text.substring(0, 12000);
        }
        return text;
    }

    /**
     * Builds the OpenAPI Schema structure to guarantee the model's response matches exactly.
     */
    private ObjectNode createOrderResponseSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "OBJECT");

        ObjectNode properties = schema.putObject("properties");
        properties.putObject("orderId").put("type", "STRING");
        properties.putObject("orderDate").put("type", "STRING");

        ObjectNode itemsSchema = properties.putObject("items");
        itemsSchema.put("type", "ARRAY");
        ObjectNode itemItems = itemsSchema.putObject("items");
        itemItems.put("type", "OBJECT");

        ObjectNode itemProps = itemItems.putObject("properties");
        itemProps.putObject("name").put("type", "STRING");
        itemProps.putObject("quantity").put("type", "STRING");
        itemProps.putObject("price").put("type", "NUMBER");

        ObjectNode categoryEnum = itemProps.putObject("category");
        categoryEnum.put("type", "STRING");
        ArrayNode enums = categoryEnum.putArray("enum");
        enums.add("Healthy").add("Snacks").add("Meats").add("Other");

        return schema;
    }

    private String constructPrompt(String subject, String cleanBody) {
        return "Extract matching details from this BigBasket confirmation email.\n" +
                "EMAIL SUBJECT: " + subject + "\n" +
                "EMAIL BODY:\n" + cleanBody + "\n\n" +
                "CRITICAL EXTRACTION RULE:\n" +
                "For the item price, you MUST extract the **Sub Total** (the final total price charged for that item row based on quantity), NOT the individual item unit price. For example, if an item says '2 units x Rs. 40 = Rs. 80', extract 80.00.\n\n" +
                "CATEGORIZATION RULES:\n" +
                "- 'Healthy': Raw fresh vegetables, fruits, whole grains, nuts, seeds, organic oils, eggs, paneer, tofu.\n" +
                "- 'Snacks': Chips, namkeen, biscuits, chocolates, candies, sodas, carbonated drinks, instant noodles.\n" +
                "- 'Meats': Fresh poultry, meat, fish, seafood.\n" +
                "- 'Other': Cleaning supplies, household essentials, atta, white sugar, regular white rice, cow milk, butter, cheese, bread.";
    }
}