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
            String prompt = constructPrompt(emailSubject, emailBody);

            // Construct JSON request body for Gemini API
            ObjectNode rootNode = objectMapper.createObjectNode();
            ArrayNode contentsArray = rootNode.putArray("contents");
            ObjectNode contentNode = contentsArray.addObject();
            ArrayNode partsArray = contentNode.putArray("parts");
            partsArray.addObject().put("text", prompt);

            ObjectNode genConfig = rootNode.putObject("generationConfig");
            genConfig.put("responseMimeType", "application/json");

            String requestBody = objectMapper.writeValueAsString(rootNode);

            // Send request to Gemini 2.5 Flash API
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(45))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.error("Gemini API returned error status: {}. Response: {}", response.statusCode(), response.body());
                return null;
            }

            // Extract the generated JSON string
            JsonNode responseJson = objectMapper.readTree(response.body());
            JsonNode candidates = responseJson.path("candidates");
            if (candidates.isMissingNode() || candidates.size() == 0) {
                logger.error("No completion candidates returned by Gemini: {}", response.body());
                return null;
            }

            String jsonText = candidates.get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            logger.info("Successfully received response from Gemini API.");
            logger.debug("Raw JSON from Gemini: {}", jsonText);

            // Parse returned JSON text into Order model
            JsonNode orderJson = objectMapper.readTree(jsonText);

            String orderId = orderJson.path("orderId").asText(null);
            String orderDate = orderJson.path("orderDate").asText(null);

            // In case Gemini fails to find order ID or Date in the email
            if (orderId == null || orderId.trim().isEmpty() || "null".equalsIgnoreCase(orderId)) {
                logger.warn("Gemini could not find orderId in the email. Using subject hash.");
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

        } catch (Exception e) {
            logger.error("Failed to call or parse Gemini API response", e);
            return null;
        }
    }

    private String constructPrompt(String subject, String htmlBody) {
        return "You are an expert order confirmation receipts parser and item classifier.\n" +
                "Given the subject and the HTML body of a BigBasket order email, extract details and structure them. \n" +
                "\n" +
                "EMAIL SUBJECT: " + subject + "\n" +
                "EMAIL HTML BODY:\n" + htmlBody + "\n\n" +
                "INSTRUCTIONS:\n" +
                "1. Find and extract the unique 'orderId' (sometimes formatted as 'Order ID: 12345678' or similar in the text).\n" +
                "2. Find and extract the 'orderDate' in 'YYYY-MM-DD' format.\n" +
                "3. Find the table or list of items ordered. For each item, extract:\n" +
                "   - 'name': The brand and product name (e.g. 'Fresho Tomato - Local', 'Parle-G Biscuits').\n" +
                "   - 'quantity': The pack size, volume, weight, or count (e.g. '1 kg', '500 g', '1 pc', 'Pack of 2').\n" +
                "   - 'price': The final actual price charged for the item (numeric double, in INR. Do not include currency symbols).\n" +
                "   - 'category': Classify the item into EXACTLY one of these four categories based on semantic understanding of the food/product name:\n" +
                "     - 'Healthy': Raw fresh vegetables, fresh fruits, whole grains (brown rice, quinoa, millet), oats, sprouts, seeds (chia, flax), unsalted organic raw nuts, green tea, fresh eggs, paneer/tofu, unsweetened almond/soy milk, sugar-free health supplements, organic cooking oils (olive oil, virgin coconut oil).\n" +
                "     - 'Snacks': Potato chips, crisps, namkeen, bhujia, cookies, cream biscuits, chocolates, candies, sugary carbonated soft drinks, sodas, energy drinks, ice creams, ready-to-eat instant noodles, pasta, packaged cakes.\n" +
                "     - 'Meats': Raw fresh poultry (chicken breasts, drumsticks), red meat (mutton, pork, beef), fresh fish, prawns, crabs, other seafood.\n" +
                "     - 'Other': Standard household necessities and regular pantry items. This includes cleaning supplies, detergents, soaps, shampoos, personal care products, regular flour/atta, regular white rice, table salt, refined white sugar, regular refined cooking oils, cow milk, butter, regular cheese, and white bread.\n" +
                "\n" +
                "RESPONSE FORMAT:\n" +
                "You must return the parsed results strictly in JSON matching the schema below. Do not include markdown code block syntax (like ```json) in the response text itself, just raw JSON:\n" +
                "{\n" +
                "  \"orderId\": \"string\",\n" +
                "  \"orderDate\": \"string in YYYY-MM-DD format\",\n" +
                "  \"items\": [\n" +
                "    {\n" +
                "      \"name\": \"string\",\n" +
                "      \"quantity\": \"string\",\n" +
                "      \"price\": 123.45,\n" +
                "      \"category\": \"Healthy | Snacks | Meats | Other\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }
}
