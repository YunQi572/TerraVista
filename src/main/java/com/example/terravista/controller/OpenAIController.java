package com.example.terravista.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/ai")
public class OpenAIController {
        private static final Logger logger = LoggerFactory.getLogger(OpenAIController.class);

        @Value("${ai.config.deepseek.apiKey}")
        private String apiKey;

        @Value("${ai.config.deepseek.baseUrl}")
        private String baseUrl;

        private final RestTemplate restTemplate = new RestTemplate();
        private final ObjectMapper objectMapper = new ObjectMapper();

        private String cleanResponse(String content) {
                // 移除所有代码块标记
                String cleaned = content.replaceAll("(?i)```json\\s*", "")
                        .replaceAll("```", "")
                        .trim();

                // 智能补全JSON结构
                int firstBrace = cleaned.indexOf('{');
                int lastBrace = cleaned.lastIndexOf('}');

                if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                        return cleaned.substring(firstBrace, lastBrace + 1);
                }
                return cleaned;
        }

        private boolean isValidStructure(JsonNode rootNode) {
                try {
                        if (!rootNode.has("title") || !rootNode.has("items")) return false;

                        JsonNode items = rootNode.get("items");
                        if (!items.isArray()) return false;

                        for (JsonNode item : items) {
                                if (!item.has("name") || !item.has("desc")) return false;
                        }
                        return true;
                } catch (Exception e) {
                        logger.error("Structure validation error: {}", e.getMessage());
                        return false;
                }
        }

        @PostMapping("/chat")
        public ResponseEntity<?> chat(@RequestBody Map<String, Object> request) {
                try {
                        String message = (String) request.get("message");
                        List<Map<String, Object>> history = (List<Map<String, Object>>) request.get("history");

                        // 请求验证和消息构建
                        List<Map<String, String>> messages = new ArrayList<>();
                        messages.add(Map.of(
                                "role", "system",
                                "content", "你是一个专业的旅游助手，请严格按以下JSON格式响应：\n" +
                                        "{\"title\": \"推荐标题\", \"items\": [{\"name\":\"景点名\", \"desc\":\"景点描述\"}]}\n" +
                                        "不要使用代码块标记，直接输出纯JSON"
                        ));

                        // 历史消息处理
                        if (history != null) {
                                history.forEach(msg -> {
                                        if (msg.containsKey("role") && msg.containsKey("content")) {
                                                messages.add(Map.of(
                                                        "role", msg.get("role").toString(),
                                                        "content", msg.get("content").toString()
                                                ));
                                        }
                                });
                        }

                        // 构建API请求
                        Map<String, Object> apiRequest = new HashMap<>();
                        apiRequest.put("model", "deepseek-chat");
                        apiRequest.put("messages", messages);
                        apiRequest.put("temperature", 0.7);
                        apiRequest.put("max_tokens", 2000);

                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        headers.set("Authorization", "Bearer " + apiKey);

                        // API调用
                        ResponseEntity<Map> response = restTemplate.exchange(
                                baseUrl,
                                HttpMethod.POST,
                                new HttpEntity<>(apiRequest, headers),
                                Map.class
                        );

                        // 响应处理
                        if (response.getStatusCode().is2xxSuccessful() && response.hasBody()) {
                                Map<String, Object> responseBody = response.getBody();
                                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");

                                if (choices != null && !choices.isEmpty()) {
                                        Map<String, Object> messageObj = (Map<String, Object>) choices.get(0).get("message");
                                        String rawContent = (String) messageObj.get("content");

                                        // 清理响应内容
                                        String cleanedContent = cleanResponse(rawContent);
                                        logger.info("Cleaned content: {}", cleanedContent);

                                        try {
                                                JsonNode jsonNode = objectMapper.readTree(cleanedContent);
                                                if (isValidStructure(jsonNode)) {
                                                        return ResponseEntity.ok()
                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                .body(jsonNode);
                                                }
                                                logger.warn("Invalid structure: {}", cleanedContent);
                                        } catch (IOException e) {
                                                logger.error("JSON解析失败: {}", cleanedContent);
                                        }
                                }
                        }

                        // 降级处理
                        return ResponseEntity.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(Collections.singletonMap("error", "AI响应格式异常"));

                } catch (Exception e) {
                        logger.error("Chat error: {}", e.getMessage());
                        return ResponseEntity.status(500)
                                .body(Collections.singletonMap("error", "服务暂时不可用"));
                }
        }
}