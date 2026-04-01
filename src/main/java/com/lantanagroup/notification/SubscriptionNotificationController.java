package com.lantanagroup.notification;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import com.fasterxml.jackson.annotation.JsonInclude;

@RestController
@RequestMapping("/notification")
public class SubscriptionNotificationController {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionNotificationController.class);

    private static final int MAX_NOTIFICATIONS = 100;

    // In-memory storage for received notifications (structured)
    private final List<NotificationRecord> receivedNotifications = Collections.synchronizedList(new ArrayList<>());

    private final ObjectMapper objectMapper;

    public SubscriptionNotificationController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostMapping("/subscription-hook")
    public ResponseEntity<String> receiveNotification(
            @RequestBody String notification,
            @RequestHeader Map<String, String> headers
    ) {
        Instant now = Instant.now();
        logger.info("POST Received at {} with headers: {}", now, headers);
        JsonNode jsonNode = null;
        try {
            jsonNode = objectMapper.readTree(notification);
            logger.info("POST Received subscription notification payload:\n{}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode));
        } catch (Exception e) {
            logger.warn("Failed to parse/pretty-print JSON payload: {}", e.getMessage());
            logger.info("POST Received subscription notification payload (raw):\n{}", notification);
        }
        synchronized (receivedNotifications) {
            if (receivedNotifications.size() >= MAX_NOTIFICATIONS) {
                receivedNotifications.remove(0);
            }
            receivedNotifications.add(new NotificationRecord("POST", now, headers, jsonNode));
        }
        return ResponseEntity.ok(notification);
    }

    // GET endpoint to retrieve all received notifications (structured)
    @GetMapping("/subscription-hook")
    public ResponseEntity<NotificationResponse> getReceivedNotifications() {
        List<NotificationRecord> notificationsSnapshot;
        synchronized (receivedNotifications) {
            // Copy to a snapshot so serialization uses a stable view outside the lock.
            notificationsSnapshot = new ArrayList<>(receivedNotifications);
        }
        return ResponseEntity.ok(new NotificationResponse(notificationsSnapshot.size(), notificationsSnapshot));
    }

    // DELETE endpoint to clear all stored notifications
    @DeleteMapping("/subscription-hook")
    public ResponseEntity<String> clearReceivedNotifications() {
        receivedNotifications.clear();
        logger.info("All stored notifications cleared.");
        return ResponseEntity.ok("All stored notifications cleared.");
    }

    // Notification record for structured storage
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NotificationRecord {
        public String method;
        public Instant timestamp;
        public Map<String, String> headers;
        public JsonNode payload;

        public NotificationRecord(String method, Instant timestamp, Map<String, String> headers, JsonNode payload) {
            this.method = method;
            this.timestamp = timestamp;
            this.headers = headers;
            this.payload = payload;
        }
    }

    public static class NotificationResponse {
        public int totalCount;
        public List<NotificationRecord> notifications;

        public NotificationResponse(int totalCount, List<NotificationRecord> notifications) {
            this.totalCount = totalCount;
            this.notifications = notifications;
        }
    }
}