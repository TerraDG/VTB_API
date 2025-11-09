package com.hackathon.apiscanner.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class AuthManager {
    public static String getAccessToken(String baseUrl, String clientId, String clientSecret) {
        try {
            String urlStr = baseUrl + "/auth/bank-token?client_id=" + clientId + "&client_secret=" + clientSecret;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("accept", "application/json");
            conn.setDoOutput(true);

            System.out.println("🔐 Получение токена: " + urlStr); // ← ДЛЯ ДЕБАГА

            int code = conn.getResponseCode();
            System.out.println("📞 Код ответа при получении токена: " + code); // ← ДЛЯ ДЕБАГА

            if (code == 200) {
                InputStream is = conn.getInputStream();
                String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                System.out.println("✅ Ответ от сервера: " + response); // ← ДЛЯ ДЕБАГА

                ObjectMapper mapper = new ObjectMapper();
                JsonNode json = mapper.readTree(response);
                String token = json.get("access_token").asText();
                return token;
            } else {
                // Читаем ошибку
                InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    String errorResponse = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                    System.out.println("❌ Ошибка при получении токена: " + errorResponse);
                }
                System.out.println("❌ Ошибка при получении токена: код " + code);
            }
        } catch (Exception e) {
            System.out.println("❌ Ошибка AuthManager: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
}