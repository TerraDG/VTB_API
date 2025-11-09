package com.hackathon.apiscanner.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.*;

public class ApiLoader {

    public static Map<String, List<String>> loadEndpoints(String filePath) {
        Map<String, List<String>> endpoints = new LinkedHashMap<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new File(filePath));
            JsonNode pathsNode = root.get("paths");

            if (pathsNode == null) {
                System.out.println("❌ В файле нет раздела 'paths'");
                return endpoints;
            }

            Iterator<Map.Entry<String, JsonNode>> fields = pathsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String path = entry.getKey();
                JsonNode methodsNode = entry.getValue();
                List<String> methods = new ArrayList<>();

                Iterator<Map.Entry<String, JsonNode>> methodFields = methodsNode.fields();
                while (methodFields.hasNext()) {
                    methods.add(methodFields.next().getKey().toUpperCase());
                }

                endpoints.put(path, methods);
            }

            System.out.println("📄 Найдено эндпоинтов: " + endpoints.size());
        } catch (Exception e) {
            System.out.println("❌ Ошибка ApiLoader: " + e.getMessage());
        }

        return endpoints;
    }
}
