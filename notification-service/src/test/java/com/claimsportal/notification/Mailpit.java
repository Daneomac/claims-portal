package com.claimsportal.notification;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Reads the emails Mailpit has received, through its REST API
 * (https://mailpit.axllent.org/docs/api-v1/).
 */
class Mailpit {

    private final Supplier<String> baseUrl;

    /** The URL is looked up on first use, once the container is running. */
    Mailpit(Supplier<String> baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Summaries (ID, From, To, Subject) of the messages addressed to {@code address}. */
    List<JsonNode> messagesTo(String address) {
        JsonNode response = client().get().uri("/api/v1/messages?limit=500").retrieve().body(JsonNode.class);
        return StreamSupport.stream(response.get("messages").spliterator(), false)
                .filter(message -> StreamSupport.stream(message.get("To").spliterator(), false)
                        .anyMatch(to -> address.equals(to.get("Address").asString())))
                .toList();
    }

    /** One message in full, including its {@code Text} and {@code HTML} bodies. */
    JsonNode message(String id) {
        return client().get().uri("/api/v1/message/{id}", id).retrieve().body(JsonNode.class);
    }

    private RestClient client() {
        return RestClient.create(baseUrl.get());
    }
}
