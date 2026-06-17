package com.agi.assistant.service.rag;

import com.agi.assistant.model.entity.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class WebSearchService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public WebSearchService(@Qualifier("streamingWebClient") WebClient webClient,
                            ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    public List<SearchResult> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://api.duckduckgo.com/?q=" + encodedQuery + "&format=json&no_html=1&skip_disambig=1";

            String response = webClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .block();

            if (response == null || response.isBlank()) {
                return Collections.emptyList();
            }

            return parseResults(response, maxResults);
        } catch (Exception e) {
            log.warn("Web search failed for query [{}]: {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SearchResult> parseResults(String response, int maxResults) {
        List<SearchResult> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(response);

            // Abstract (main answer)
            String abstractText = root.path("AbstractText").asText("");
            if (!abstractText.isBlank()) {
                SearchResult sr = new SearchResult();
                sr.setTitle(root.path("Heading").asText("Search Result"));
                sr.setContent(abstractText);
                sr.setSource(root.path("AbstractSource").asText("DuckDuckGo"));
                sr.setScore(1.0);
                results.add(sr);
            }

            // Related topics
            JsonNode relatedTopics = root.path("RelatedTopics");
            if (relatedTopics.isArray()) {
                for (JsonNode topic : relatedTopics) {
                    if (results.size() >= maxResults) break;
                    String text = topic.path("Text").asText("");
                    String firstUrl = topic.path("FirstURL").asText("");
                    if (!text.isBlank()) {
                        SearchResult sr = new SearchResult();
                        sr.setTitle(text.length() > 80 ? text.substring(0, 80) + "..." : text);
                        sr.setContent(text);
                        sr.setSource(firstUrl.isEmpty() ? "DuckDuckGo" : firstUrl);
                        sr.setScore(0.8);
                        results.add(sr);
                    }
                    // Handle sub-topics
                    JsonNode subTopics = topic.path("Topics");
                    if (subTopics.isArray()) {
                        for (JsonNode sub : subTopics) {
                            if (results.size() >= maxResults) break;
                            String subText = sub.path("Text").asText("");
                            String subUrl = sub.path("FirstURL").asText("");
                            if (!subText.isBlank()) {
                                SearchResult sr = new SearchResult();
                                sr.setTitle(subText.length() > 80 ? subText.substring(0, 80) + "..." : subText);
                                sr.setContent(subText);
                                sr.setSource(subUrl.isEmpty() ? "DuckDuckGo" : subUrl);
                                sr.setScore(0.6);
                                results.add(sr);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse search results: {}", e.getMessage());
        }
        return results;
    }
}
