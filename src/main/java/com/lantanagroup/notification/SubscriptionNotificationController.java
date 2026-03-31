package com.lantanagroup.notification;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

@RestController
@RequestMapping("/notification")

public class SubscriptionNotificationController {
    
    // In-memory storage for received notifications
    private final List<String> receivedNotifications = Collections.synchronizedList(new ArrayList<>());

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionNotificationController.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/subscription-hook")
    public ResponseEntity<String> receiveNotification(
            @RequestBody String notification,
            @RequestHeader Map<String, String> headers
    ) {
        Instant now = Instant.now();
        logger.info("POST Received at {} with headers: {}", now, headers);
        String prettyPayload = notification;
        try {
            JsonNode jsonNode = objectMapper.readTree(notification);
            prettyPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
        } catch (Exception e) {
            logger.warn("Failed to pretty-print JSON payload: {}", e.getMessage());
        }
        logger.info("POST Received subscription notification payload:\n{}", prettyPayload);
        receivedNotifications.add("POST @ " + now + "\nHeaders: " + headers + "\nPayload: " + prettyPayload);
        return ResponseEntity.ok(notification);
    }

    @PutMapping("/subscription-hook")
    public ResponseEntity<String> receiveNotificationPut(
            @RequestBody String notification,
            @RequestHeader Map<String, String> headers
    ) {
        Instant now = Instant.now();
        logger.info("PUT Received at {} with headers: {}", now, headers);
        String prettyPayload = notification;
        try {
            JsonNode jsonNode = objectMapper.readTree(notification);
            prettyPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
        } catch (Exception e) {
            logger.warn("Failed to pretty-print JSON payload: {}", e.getMessage());
        }
        logger.info("PUT Received subscription notification payload:\n{}", prettyPayload);
        receivedNotifications.add("PUT @ " + now + "\nHeaders: " + headers + "\nPayload: " + prettyPayload);
        return ResponseEntity.ok(notification);
    }

    // GET endpoint to retrieve all received notifications
    @GetMapping("/subscription-hook")
    public ResponseEntity<List<String>> getReceivedNotifications() {
        // Return a copy to avoid concurrent modification issues
        return ResponseEntity.ok(new ArrayList<>(receivedNotifications));
    }

    // DELETE endpoint to clear all stored notifications
    @DeleteMapping("/subscription-hook")
    public ResponseEntity<String> clearReceivedNotifications() {
        receivedNotifications.clear();
        logger.info("All stored notifications cleared.");
        return ResponseEntity.ok("All stored notifications cleared.");
    }
}