package com.hackathon.apiscanner.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackathon.apiscanner.report.HtmlReportGenerator.EndpointResult;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Загружает endpoint-data.json и умеет:
 *  - выполнить endpoint'ы, которые являются источниками (для "from")
 *  - сохранить ответы (по jsonPointer)
 *  - вернуть мапу resolvedParams: endpoint -> map(paramName -> resolvedValueAsString)
 */
public class EndpointDataProvider {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, EndpointInputSpec> specMap = new LinkedHashMap<>();
    private final Map<String, JsonNode> savedResponses = new HashMap<>();

    public EndpointDataProvider(String configPath) throws Exception {
        JsonNode root = mapper.readTree(new File(configPath));
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String endpoint = e.getKey();
            EndpointInputSpec spec = mapper.treeToValue(e.getValue(), EndpointInputSpec.class);
            specMap.put(endpoint, spec);
        }
    }

    /**
     * Получить сохранённое значение из ответа source-endpoint.
     */
    public String getSavedValue(String endpointKey, String jsonPointer) {
        JsonNode n = savedResponses.get(endpointKey);
        if (n == null) return null;
        JsonNode found = n.at(jsonPointer);
        if (found.isMissingNode()) return null;
        if (found.isTextual()) return found.asText();
        return found.toString();
    }

    /**
     * Выполнить все источники и сохранить их ответы (если задано saveResponse).
     * Возвращает map: endpoint -> map(name -> valueString) для использования в ApiTester.
     */
    public Map<String, Map<String, Object>> resolveAll(String baseUrl, String token) {
        Map<String, Map<String, Object>> result = new HashMap<>();

        for (Map.Entry<String, EndpointInputSpec> entry : specMap.entrySet()) {
            String endpoint = entry.getKey();
            EndpointInputSpec spec = entry.getValue();

            // если есть saveResponse — выполняем endpoint (если это источник)
            if (spec.saveResponse != null && !spec.saveResponse.isEmpty()) {
                try {
                    String url = buildUrlWithQuery(baseUrl, endpoint, spec);
                    JsonNode resp = executeRequestAndReadJson(url, spec.method != null ? spec.method : "GET", token, buildBodyNode(spec));
                    if (resp != null) {
                        savedResponses.put(endpoint, resp);
                        System.out.println("🔁 Выполнен source: " + endpoint);
                        System.out.println("   -> Ответ (обрезано): " + (resp.toString().length() > 200 ? resp.toString().substring(0,200) + "..." : resp.toString()));

                        JsonNode tokenNode = resp.at("/access_token");
                        if (!tokenNode.isMissingNode() && tokenNode.isTextual()) {
                            String newToken = tokenNode.asText();
                            System.out.println("🔐 Получен access_token (preview): " + newToken.substring(0, 8) + "...");
                            savedResponses.put("ACCESS_TOKEN_GLOBAL", mapper.valueToTree(newToken));
                        }

                        JsonNode consentNode = resp.at("/consent_id");
                        if (!consentNode.isMissingNode() && consentNode.isTextual()) {
                            String consentId = consentNode.asText();
                            System.out.println("🧾 Получен consent_id: " + consentId);
                        }

                    }
                } catch (Exception ex) {
                    System.out.println("⚠️ Provider: ошибка при выполнении source " + endpoint + ": " + ex.getMessage());
                }
            }
        }

        // Теперь соберём resolved map для всех endpoints
        for (Map.Entry<String, EndpointInputSpec> entry : specMap.entrySet()) {
            String endpoint = entry.getKey();
            EndpointInputSpec spec = entry.getValue();
            Map<String, Object> resolved = new HashMap<>();

            // pathParams
            if (spec.pathParams != null) {
                for (Map.Entry<String, EndpointInputSpec.SpecValue> p : spec.pathParams.entrySet()) {
                    String name = p.getKey();
                    EndpointInputSpec.SpecValue sv = p.getValue();
                    String val = resolveSpecValue(sv);
                    if (val != null) resolved.put(name, val);
                }
            }
            // headers
            if (spec.headers != null) {
                for (Map.Entry<String, EndpointInputSpec.SpecValue> p : spec.headers.entrySet()) {
                    String name = p.getKey();
                    EndpointInputSpec.SpecValue sv = p.getValue();
                    String val = resolveSpecValue(sv);
                    if (val != null) resolved.put("header:" + name, val);
                }
            }
            // query
            if (spec.query != null) {
                for (Map.Entry<String, EndpointInputSpec.SpecValue> p : spec.query.entrySet()) {
                    String name = p.getKey();
                    EndpointInputSpec.SpecValue sv = p.getValue();
                    String val = resolveSpecValue(sv);
                    if (val != null) resolved.put("query:" + name, val);
                }
            }
            // body
            if (spec.body != null && !spec.body.isEmpty()) {
                try {
                    ObjectNodeBuilder builder = new ObjectNodeBuilder(mapper);
                    for (Map.Entry<String, EndpointInputSpec.SpecValue> p : spec.body.entrySet()) {
                        String name = p.getKey();
                        EndpointInputSpec.SpecValue sv = p.getValue();
                        if (sv.value != null) builder.putRaw(name, sv.value);
                        else {
                            String val = resolveSpecValue(sv);
                            if (val != null) builder.putText(name, val);
                        }
                    }
                    JsonNode bodyNode = builder.build();
                    resolved.put("body", bodyNode.toString());
                } catch (Exception ex) {
                    // ignore
                }
            }

            result.put(endpoint, resolved);
        }

        return result;
    }

    /** Добавляет query параметры к URL */
    /** Добавляет query параметры к URL и подставляет pathParams {name} если они есть */
    private String buildUrlWithQuery(String baseUrl, String endpoint, EndpointInputSpec spec) {
        String url = baseUrl + endpoint;

        // 1) подставляем pathParams (если есть)
        if (spec.pathParams != null && !spec.pathParams.isEmpty()) {
            for (Map.Entry<String, EndpointInputSpec.SpecValue> e : spec.pathParams.entrySet()) {
                String name = e.getKey();
                String val = resolveSpecValue(e.getValue());
                if (val != null) {
                    url = url.replace("{" + name + "}", val);
                }
            }
        }

        // 2) добавляем query-параметры
        if (spec.query != null && !spec.query.isEmpty()) {
            String queryString = spec.query.entrySet().stream()
                    .map(e -> {
                        String k = e.getKey();
                        String v = resolveSpecValue(e.getValue());
                        return k + "=" + URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
                    })
                    .collect(Collectors.joining("&"));
            url = url.contains("?") ? url + "&" + queryString : url + "?" + queryString;
        }
        return url;
    }


    private String resolveSpecValue(EndpointInputSpec.SpecValue sv) {
        if (sv == null) return null;
        try {
            if (sv.value != null && !sv.value.isNull()) {
                if (sv.value.isTextual()) return sv.value.asText();
                return sv.value.toString();
            } else if (sv.from != null && sv.jsonPointer != null) {
                JsonNode source = savedResponses.get(sv.from);
                if (source != null) {
                    JsonNode found = source.at(sv.jsonPointer);
                    if (!found.isMissingNode()) {
                        return found.isTextual() ? found.asText() : found.toString();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private JsonNode buildBodyNode(EndpointInputSpec spec) {
        if (spec == null || spec.body == null || spec.body.isEmpty()) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, EndpointInputSpec.SpecValue> e : spec.body.entrySet()) {
            if (e.getValue().value != null) {
                map.put(e.getKey(), e.getValue().value);
            }
        }
        return mapper.valueToTree(map);
    }

    private JsonNode executeRequestAndReadJson(String urlString, String method, String token, JsonNode body) {
        try {
            System.out.println("➡️ HTTP " + (method != null ? method : "GET") + " -> " + urlString);
            if (token != null && !token.isEmpty()) {
                System.out.println("   Authorization: Bearer " + (token.length() > 12 ? token.substring(0,10) + "..." : token));
            }
            // если в savedResponses есть глобальный токен — покажем, откуда он
            if (savedResponses.containsKey("ACCESS_TOKEN_GLOBAL")) {
                JsonNode t = savedResponses.get("ACCESS_TOKEN_GLOBAL");
                if (t.isTextual()) System.out.println("   (token from savedResponses preview: " + t.asText().substring(0,8) + "...)");
            }

            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method != null ? method : "GET");
            conn.setRequestProperty("accept", "application/json");
            if ((token == null || token.isEmpty()) && savedResponses.containsKey("ACCESS_TOKEN_GLOBAL")) {
                JsonNode tokenNode = savedResponses.get("ACCESS_TOKEN_GLOBAL");
                if (tokenNode.isTextual()) {
                    token = tokenNode.asText();
                }
            }
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null && !body.isNull()) {
                conn.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();

            // ✅ Обрабатываем успешные коды без тела (204 No Content)
            if (code == 204) {
                conn.disconnect();
                return mapper.createObjectNode(); // пустой JSON, чтобы не считалось ошибкой
            }

            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                conn.disconnect();
                return mapper.createObjectNode(); // безопасно вернуть пустой JSON
            }

            JsonNode json = mapper.readTree(is);
            conn.disconnect();
            return json;

        } catch (Exception e) {
            return null;
        }
    }

    // Вспомогательный простой билдер JSON-объекта
    private static class ObjectNodeBuilder {
        private final ObjectMapper mapper;
        private final com.fasterxml.jackson.databind.node.ObjectNode node;
        ObjectNodeBuilder(ObjectMapper mapper) {
            this.mapper = mapper;
            this.node = mapper.createObjectNode();
        }
        void putText(String k, String v) { node.put(k, v); }
        void putRaw(String k, JsonNode raw) { node.set(k, raw); }
        JsonNode build() { return node; }
    }

    public Map<String, com.fasterxml.jackson.databind.JsonNode> getSavedResponses() {
        return savedResponses;
    }
}
