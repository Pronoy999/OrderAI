package com.orderai.sheets;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.orderai.model.Order;
import com.orderai.model.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SheetsClient {
    private static final Logger logger = LoggerFactory.getLogger(SheetsClient.class);

    private final String spreadsheetId;
    private final Sheets sheetsService;

    public SheetsClient(String spreadsheetId, String rawJsonCredentials) throws Exception {
        this.spreadsheetId = spreadsheetId;

        String credentialsStr = rawJsonCredentials.trim();

        // Auto-detect Base64: If it doesn't start with a JSON bracket '{', decode it first!
        if (!credentialsStr.startsWith("{")) {
            logger.info("Base64 layout detected for Google Sheets credentials. Decoding string...");
            byte[] decodedBytes = Base64.getDecoder().decode(credentialsStr);
            credentialsStr = new String(decodedBytes, StandardCharsets.UTF_8);
        }

        // Convert the validated JSON string directly into a byte stream for Google Auth
        ByteArrayInputStream credentialsStream = new ByteArrayInputStream(
                credentialsStr.getBytes(StandardCharsets.UTF_8)
        );

        GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream)
                .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));

        // Assemble the authenticated Sheet interaction portal
        this.sheetsService = new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("OrderAI-Logger")
                .build();

        logger.info("Successfully established connection to Google Sheets API.");
    }

    public boolean isOrderAlreadyLogged(String orderId) {
        if (orderId == null || orderId.isEmpty()) {
            return false;
        }

        try {
            // Read all cells in Column A to find if orderId is already present
            // We use the default first sheet by using just "A:A" range
            String range = "A:A";
            ValueRange response = sheetsService.spreadsheets().values()
                    .get(spreadsheetId, range)
                    .execute();

            List<List<Object>> values = response.getValues();
            if (values == null || values.isEmpty()) {
                return false;
            }

            for (List<Object> row : values) {
                if (!row.isEmpty() && orderId.equals(row.get(0).toString().trim())) {
                    return true;
                }
            }

        } catch (Exception e) {
            logger.warn("Could not read column A to check duplicates (this is normal if sheet is empty): {}", e.getMessage());
        }

        return false;
    }

    public void logOrder(Order order) {
        logger.info("Logging Order ID: {} to Google Sheet...", order.getOrderId());
        try {
            // Check if sheet is empty and needs headers
            ensureHeaderRow();

            List<List<Object>> rowsToAppend = new ArrayList<>();
            for (OrderItem item : order.getItems()) {
                List<Object> row = Arrays.asList(
                        order.getOrderId(),
                        order.getOrderDate() != null ? order.getOrderDate() : "",
                        item.getName(),
                        item.getQuantity(),
                        item.getPrice(),
                        item.getCategory()
                );
                rowsToAppend.add(row);
            }

            ValueRange body = new ValueRange().setValues(rowsToAppend);

            // Append starting at the next empty row in columns A to F
            String range = "A:F";
            AppendValuesResponse result = sheetsService.spreadsheets().values()
                    .append(spreadsheetId, range, body)
                    .setValueInputOption("USER_ENTERED")
                    .execute();

            logger.info("Successfully appended {} rows to Google Sheet.", result.getUpdates().getUpdatedRows());

        } catch (Exception e) {
            logger.error("Failed to append order to Google Sheets", e);
        }
    }

    private void ensureHeaderRow() {
        try {
            String range = "A1:F1";
            ValueRange response = sheetsService.spreadsheets().values()
                    .get(spreadsheetId, range)
                    .execute();

            List<List<Object>> values = response.getValues();
            if (values == null || values.isEmpty() || values.get(0).isEmpty()) {
                logger.info("Sheet appears to be empty. Creating header row...");

                List<List<Object>> headerValues = Collections.singletonList(
                        Arrays.asList("Order ID", "Order Date", "Item Name", "Quantity", "Price", "Category")
                );

                ValueRange headerBody = new ValueRange().setValues(headerValues);
                sheetsService.spreadsheets().values()
                        .append(spreadsheetId, "A:F", headerBody)
                        .setValueInputOption("USER_ENTERED")
                        .execute();
            }
        } catch (Exception e) {
            logger.warn("Unable to verify/create header row: {}", e.getMessage());
        }
    }
}
