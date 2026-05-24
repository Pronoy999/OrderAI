package com.orderai.gmail;

import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class GmailClient {
    private static final Logger logger = LoggerFactory.getLogger(GmailClient.class);

    private final String email;
    private final String appPassword;

    public GmailClient(String email, String appPassword) {
        this.email = email;
        this.appPassword = appPassword;
    }

    public static class EmailMessage {
        private final String subject;
        private final Date receivedDate;
        private final String body;

        public EmailMessage(String subject, Date receivedDate, String body) {
            this.subject = subject;
            this.receivedDate = receivedDate;
            this.body = body;
        }

        public String getSubject() {
            return subject;
        }

        public Date getReceivedDate() {
            return receivedDate;
        }

        public String getBody() {
            return body;
        }
    }

    public List<EmailMessage> fetchBigBasketEmails(int daysBack) {
        List<EmailMessage> matchingEmails = new ArrayList<>();
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.host", "imap.gmail.com");
        properties.put("mail.imaps.port", "993");
        properties.put("mail.imaps.ssl.enable", "true");

        Session session = Session.getInstance(properties);
        Store store = null;
        Folder inbox = null;

        try {
            logger.info("Connecting to Gmail IMAP server...");
            store = session.getStore("imaps");
            store.connect(email, appPassword);
            logger.info("Successfully connected to Gmail IMAP.");

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            // 1. Compute the cutoff date for server-side filtering
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -daysBack);
            Date cutoffDate = cal.getTime();

            // 2. Build NATIVE IMAP Search Terms (Executes purely on Gmail's side)
            SearchTerm fromBB = new FromStringTerm("bigbasket.com");
            SearchTerm subjectBB = new SubjectTerm("BigBasket");
            SearchTerm senderOrSubject = new OrTerm(fromBB, subjectBB);

            // Filter by date right at the server level so old emails are never downloaded
            SearchTerm recentTerm = new ReceivedDateTerm(ComparisonTerm.GE, cutoffDate);
            SearchTerm finalSearchCriteria = new AndTerm(senderOrSubject, recentTerm);

            // This array will now ONLY contain recent BigBasket emails
            Message[] messages = inbox.search(finalSearchCriteria);
            logger.info("Found {} total recent BigBasket messages in INBOX via server-side search.", messages.length);

            int processedCount = 0;
            // Process from newest to oldest
            for (int i = messages.length - 1; i >= 0; i--) {
                Message message = messages[i];
                Date receivedDate = message.getReceivedDate();
                if (receivedDate == null) {
                    receivedDate = message.getSentDate();
                }

                processedCount++;
                String subject = message.getSubject();
                logger.info("Processing Email #{} - Subject: '{}' - Date: {}", processedCount, subject, receivedDate);

                try {
                    String rawBody = getTextFromMessage(message);
                    String cleanBody = cleanHtmlContent(rawBody);
                    matchingEmails.add(new EmailMessage(subject, receivedDate, cleanBody));
                } catch (Exception e) {
                    logger.error("Failed to parse body for email: {}", subject, e);
                }

                // Safety check limit
                if (processedCount >= 20) {
                    logger.info("Reached processing limit of 20 emails. Stopping fetch.");
                    break;
                }
            }

        } catch (Exception e) {
            logger.error("Error fetching emails from Gmail IMAP", e);
        } finally {
            try {
                if (inbox != null && inbox.isOpen()) {
                    inbox.close(false);
                }
                if (store != null) {
                    store.close();
                }
            } catch (Exception e) {
                logger.error("Error closing IMAP resources", e);
            }
        }

        return matchingEmails;
    }

    private String getTextFromMessage(Message message) throws MessagingException, IOException {
        if (message.isMimeType("text/plain")) {
            return message.getContent().toString();
        } else if (message.isMimeType("text/html")) {
            return message.getContent().toString();
        } else if (message.isMimeType("multipart/*")) {
            MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
            return getTextFromMimeMultipart(mimeMultipart);
        }
        return "";
    }

    private String getTextFromMimeMultipart(MimeMultipart mimeMultipart) throws MessagingException, IOException {
        StringBuilder result = new StringBuilder();
        int count = mimeMultipart.getCount();
        String htmlPart = null;
        String textPart = null;

        for (int i = 0; i < count; i++) {
            BodyPart bodyPart = mimeMultipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain")) {
                textPart = bodyPart.getContent().toString();
            } else if (bodyPart.isMimeType("text/html")) {
                htmlPart = bodyPart.getContent().toString();
            } else if (bodyPart.isMimeType("multipart/*")) {
                result.append(getTextFromMimeMultipart((MimeMultipart) bodyPart.getContent()));
            }
        }

        // Prefer HTML because BigBasket receipts have structured HTML tables
        if (htmlPart != null) {
            return htmlPart;
        } else if (textPart != null) {
            return textPart;
        }
        return result.toString();
    }

    private String cleanHtmlContent(String rawHtml) {
        if (rawHtml == null || rawHtml.trim().isEmpty()) {
            return "";
        }
        try {
            // Strip out style tags, script tags, head tags and keep body tables
            Document doc = Jsoup.parse(rawHtml);
            doc.select("style, script, head, link, meta, img").remove();
            
            // Extract the core text or clean body to make the prompt size as small as possible
            String clean = doc.body().html();
            // Optional: normalize extra whitespaces
            clean = clean.replaceAll("(?s)<style>.*?</style>", "")
                         .replaceAll("(?s)<script>.*?</script>", "")
                         .replaceAll("\\s+", " ")
                         .trim();
            return clean;
        } catch (Exception e) {
            logger.warn("Failed to sanitize HTML with JSoup, returning original content truncated", e);
            return rawHtml.length() > 5000 ? rawHtml.substring(0, 5000) : rawHtml;
        }
    }
}
