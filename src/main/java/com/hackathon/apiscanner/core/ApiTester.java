package com.hackathon.apiscanner.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackathon.apiscanner.report.HtmlReportGenerator.EndpointResult;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class ApiTester {

    /**
     * Основная функция тестирования эндпоинтов
     */

    public static List<EndpointResult> testEndpoints(
            String baseUrl,
            String token,
            Map<String, List<String>> endpoints,
            Map<String, Map<String, Object>> endpointDataConfig
    ) {
        List<EndpointResult> results = new ArrayList<>();
        System.out.println("\n=== 🔍 Начало тестирования эндпоинтов ===\n");

        for (Map.Entry<String, List<String>> entry : endpoints.entrySet()) {
            String path = entry.getKey();
            List<String> methods = entry.getValue();

            for (String method : methods) {
                String urlString = baseUrl + path;
                HttpURLConnection conn = null;
                long timeMs = 0;
                int code = -1;
                boolean success = false;

                try {
                    // Подставляем query параметры, если есть
                    Map<String, Object> endpointData =
                            (Map<String, Object>)(Map<?, ?>) endpointDataConfig.getOrDefault(path, Collections.emptyMap());

                    if (endpointData.containsKey("query")) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> query = (Map<String, String>) endpointData.get("query");
                        String queryString = query.entrySet().stream()
                                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                                .collect(Collectors.joining("&"));
                        urlString = baseUrl + path + "?" + queryString;
                    }

                    URL url = new URL(urlString);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod(method.toUpperCase());
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("Accept", "application/json");

                    // Добавляем токен
                    if (token != null && !token.isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + token);
                    }

                    // Добавляем заголовки, если есть
                    if (endpointData.containsKey("headers")) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> headers = (Map<String, String>) endpointData.get("headers");
                        for (Map.Entry<String, String> h : headers.entrySet()) {
                            conn.setRequestProperty(h.getKey(), h.getValue());
                        }
                    }

                    // Добавляем тело, если есть
                    if (endpointData.containsKey("body")) {
                        conn.setDoOutput(true);
                        ObjectMapper mapper = new ObjectMapper();
                        String body = mapper.writeValueAsString(endpointData.get("body"));
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(body.getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    // Отправляем запрос
                    Instant start = Instant.now();
                    code = conn.getResponseCode();
                    Instant end = Instant.now();
                    timeMs = Duration.between(start, end).toMillis();

                    success = (code >= 200 && code < 400);

                    System.out.printf("[%s] %-70s -> %d (%s, %d ms)%n",
                            method, urlString, code, success ? "✅ OK" : "❌ FAIL", timeMs);

                } catch (Exception e) {
                    System.out.printf("[%s] %-70s -> ❌ ERROR (%s)%n", method, path, e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                    results.add(new EndpointResult(method, urlString, code, timeMs, success));
                }
            }
        }

        return results;
    }


    public static List<EndpointResult> runConsentEndpointTests(String baseUrl, String token, String teamId) {
        System.out.println("\n=== 🔐 Consent endpoint tests ===");
        class TestCase {
            String name;
            String method;
            String url;
            Map<String, String> headers;
            String body;
            int expectMin;
            int expectMax;
        }

        List<TestCase> tests = new ArrayList<>();
        List<EndpointResult> results = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        // -----------------------
        // 1) Happy path (создать consent) — добавим как тест, но также выполним отдельно для lifecycle
        TestCase happy = new TestCase();
        happy.name = "Happy path (create consent)";
        happy.method = "POST";
        happy.url = baseUrl + "/account-consents/request";
        happy.headers = Map.of(
                "Authorization", "Bearer " + token,
                "X-Requesting-Bank", teamId,
                "Content-Type", "application/json",
                "Accept", "application/json"
        );
        happy.body = """
        {
          "client_id":"%s-1",
          "permissions":["ReadAccountsDetail","ReadBalances"],
          "reason":"Test consent creation",
          "requesting_bank":"%s",
          "requesting_bank_name":"Test App"
        }
        """.formatted(teamId, teamId);
        happy.expectMin = 200; happy.expectMax = 299;
        tests.add(happy);

        // 2) Missing Authorization
        TestCase noAuth = new TestCase();
        noAuth.name = "Missing Authorization";
        noAuth.method = "POST";
        noAuth.url = baseUrl + "/account-consents/request";
        noAuth.headers = Map.of("X-Requesting-Bank", teamId, "Content-Type", "application/json");
        noAuth.body = "{}";
        noAuth.expectMin = 400; noAuth.expectMax = 499;
        tests.add(noAuth);

        // 3) Invalid token
        TestCase invalidTok = new TestCase();
        invalidTok.name = "Invalid token";
        invalidTok.method = "POST";
        invalidTok.url = baseUrl + "/account-consents/request";
        invalidTok.headers = Map.of("Authorization", "Bearer invalid.token.here", "X-Requesting-Bank", teamId, "Content-Type", "application/json");
        invalidTok.body = "{}";
        invalidTok.expectMin = 400; invalidTok.expectMax = 499;
        tests.add(invalidTok);

        // 4) SQL Injection simulation (client_id)
        TestCase sqlInj = new TestCase();
        sqlInj.name = "SQL Injection Simulation";
        sqlInj.method = "POST";
        sqlInj.url = baseUrl + "/account-consents/request";
        sqlInj.headers = Map.of("Authorization", "Bearer " + token, "X-Requesting-Bank", teamId, "Content-Type", "application/json");
        sqlInj.body = """
        {
          "client_id":"teamX' OR '1'='1",
          "permissions":["ReadAccountsDetail"],
          "requesting_bank":"%s"
        }
        """.formatted(teamId);
        sqlInj.expectMin = 400; sqlInj.expectMax = 499;
        tests.add(sqlInj);

        // 5) XSS injection in reason
        TestCase xss = new TestCase();
        xss.name = "XSS Injection (reason)";
        xss.method = "POST";
        xss.url = baseUrl + "/account-consents/request";
        xss.headers = Map.of("Authorization", "Bearer " + token, "X-Requesting-Bank", teamId, "Content-Type", "application/json");
        xss.body = """
        {
          "client_id":"%s-1",
          "permissions":["ReadAccountsDetail"],
          "reason":"<script>alert(1)</script>",
          "requesting_bank":"%s",
          "requesting_bank_name":"XSS Test"
        }
        """.formatted(teamId, teamId);
        xss.expectMin = 400; xss.expectMax = 499;
        tests.add(xss);

        // 6) Missing X-Requesting-Bank header
        TestCase noXReq = new TestCase();
        noXReq.name = "Missing X-Requesting-Bank";
        noXReq.method = "POST";
        noXReq.url = baseUrl + "/account-consents/request";
        noXReq.headers = Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json");
        noXReq.body = "{\"client_id\":\"" + teamId + "-1\"}";
        noXReq.expectMin = 400; noXReq.expectMax = 499;
        tests.add(noXReq);

        // 7) Wrong X-Requesting-Bank value (client suffix)
        TestCase wrongX = new TestCase();
        wrongX.name = "Wrong X-Requesting-Bank (client suffix)";
        wrongX.method = "POST";
        wrongX.url = baseUrl + "/account-consents/request";
        wrongX.headers = Map.of("Authorization", "Bearer " + token, "X-Requesting-Bank", teamId + "-1", "Content-Type", "application/json");
        wrongX.body = """
        {
          "client_id":"%s-1",
          "permissions":["ReadAccountsDetail"],
          "requesting_bank":"%s",
          "requesting_bank_name":"Bad"
        }
        """.formatted(teamId, teamId);
        wrongX.expectMin = 400; wrongX.expectMax = 499;
        tests.add(wrongX);

        // 8) Wrong Authorization scheme
        TestCase wrongScheme = new TestCase();
        wrongScheme.name = "Wrong Authorization scheme (BearerX)";
        wrongScheme.method = "POST";
        wrongScheme.url = baseUrl + "/account-consents/request";
        wrongScheme.headers = Map.of("Authorization", "BearerX " + token, "X-Requesting-Bank", teamId, "Content-Type", "application/json");
        wrongScheme.body = "{\"client_id\":\"" + teamId + "-1\"}";
        wrongScheme.expectMin = 400; wrongScheme.expectMax = 499;
        tests.add(wrongScheme);

        // 9) Wrong Content-Type
        TestCase wrongCt = new TestCase();
        wrongCt.name = "Wrong Content-Type";
        wrongCt.method = "POST";
        wrongCt.url = baseUrl + "/account-consents/request";
        wrongCt.headers = Map.of("Authorization", "Bearer " + token, "X-Requesting-Bank", teamId, "Content-Type", "text/plain");
        wrongCt.body = "{\"client_id\":\"" + teamId + "-1\"}";
        wrongCt.expectMin = 400; wrongCt.expectMax = 499;
        tests.add(wrongCt);

        // 10) Large permissions list (fuzz)
        TestCase largePerms = new TestCase();
        largePerms.name = "Large permissions list (fuzz)";
        largePerms.method = "POST";
        largePerms.url = baseUrl + "/account-consents/request";
        largePerms.headers = Map.of("Authorization", "Bearer " + token, "X-Requesting-Bank", teamId, "Content-Type", "application/json");
        StringBuilder perms = new StringBuilder();
        perms.append("\"permissions\":[");
        for (int i = 0; i < 100; i++) perms.append("\"p").append(i).append("\",");
        perms.append("\"ReadAccountsDetail\"]");
        largePerms.body = "{ \"client_id\":\"" + teamId + "-1\"," + perms.toString() + "}";
        largePerms.expectMin = 400; largePerms.expectMax = 499;
        tests.add(largePerms);

        // -----------------------
        // Выполняем перечисленные тесты
        for (TestCase tc : tests) {
            try {
                long t0 = System.currentTimeMillis();
                HttpURLConnection conn = (HttpURLConnection) new URL(tc.url).openConnection();
                conn.setRequestMethod(tc.method);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (tc.headers != null) for (var h : tc.headers.entrySet()) conn.setRequestProperty(h.getKey(), h.getValue());
                if (tc.body != null && !tc.body.isBlank()) {
                    conn.setDoOutput(true);
                    byte[] b = tc.body.getBytes(StandardCharsets.UTF_8);
                    conn.setRequestProperty("Content-Length", String.valueOf(b.length));
                    try (OutputStream os = conn.getOutputStream()) { os.write(b); }
                }
                int code = conn.getResponseCode();
                java.io.InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                String resp = "";
                if (is != null) resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                long dt = System.currentTimeMillis() - t0;
                boolean pass = code >= tc.expectMin && code <= tc.expectMax;
                results.add(new EndpointResult(tc.method, tc.url, code, dt, pass, tc.name));
                System.out.printf("[%s] %s -> %d (%s, %d ms)%n    resp: %s%n",
                        pass ? "OK" : "FAIL", tc.name, code, pass ? "expected" : "unexpected", dt,
                        resp.length() > 300 ? resp.substring(0, 300) + "..." : resp);
                conn.disconnect();
            } catch (Exception e) {
                results.add(new EndpointResult(tc.method, tc.url, -1, 0, false, tc.name + " ERROR: " + e.getMessage()));
                System.out.printf("[ERR] %s -> %s%n", tc.name, e.getMessage());
            }
        }

        // -----------------------
        // 11) Lifecycle tests: create -> get -> delete -> get
        System.out.println("\n=== Lifecycle tests (create -> get -> delete -> get) ===");
        String createdConsentId = null;
        try {
            // create
            HttpURLConnection c1 = (HttpURLConnection) new URL(baseUrl + "/account-consents/request").openConnection();
            c1.setRequestMethod("POST");
            c1.setRequestProperty("Authorization", "Bearer " + token);
            c1.setRequestProperty("X-Requesting-Bank", teamId);
            c1.setRequestProperty("Content-Type", "application/json");
            c1.setDoOutput(true);
            String createBody = """
            {
              "client_id":"%s-1",
              "permissions":["ReadAccountsDetail","ReadBalances"],
              "reason":"lifecycle test",
              "requesting_bank":"%s",
              "requesting_bank_name":"Lifecycle Test"
            }
            """.formatted(teamId, teamId);
            try (OutputStream os = c1.getOutputStream()) { os.write(createBody.getBytes(StandardCharsets.UTF_8)); }
            int code1 = c1.getResponseCode();
            java.io.InputStream is1 = (code1 >= 200 && code1 < 400) ? c1.getInputStream() : c1.getErrorStream();
            String resp1 = is1 != null ? new String(is1.readAllBytes(), StandardCharsets.UTF_8) : "";
            boolean passCreate = code1 >= 200 && code1 < 300;
            results.add(new EndpointResult("POST", baseUrl + "/account-consents/request", code1, 0, passCreate, "Lifecycle: create"));
            System.out.println("[Lifecycle create] -> " + code1 + " " + (resp1.length() > 200 ? resp1.substring(0,200) + "..." : resp1));
            if (passCreate) {
                try {
                    var node = mapper.readTree(resp1);
                    // Попробуем разные поля: consent_id или consentId или data/consentId — гибко
                    if (node.has("consent_id")) createdConsentId = node.get("consent_id").asText();
                    else if (node.has("consentId")) createdConsentId = node.get("consentId").asText();
                    else if (node.has("data") && node.get("data").has("consentId")) createdConsentId = node.get("data").get("consentId").asText();
                } catch (Exception ex) {
                    // ignore
                }
                if (createdConsentId != null) {
                    System.out.println("🧾 Получен consent_id: " + createdConsentId);
                } else {
                    System.out.println("⚠️ Не удалось извлечь consent_id из ответа create");
                }
            }
            c1.disconnect();
        } catch (Exception e) {
            System.out.println("[ERR] lifecycle create -> " + e.getMessage());
        }

        // Если получили consent_id — делаем GET, DELETE, GET
        if (createdConsentId != null) {
            try {
                // GET by id
                String getUrl = baseUrl + "/account-consents/" + createdConsentId;
                long t0 = System.currentTimeMillis();
                HttpURLConnection g = (HttpURLConnection) new URL(getUrl).openConnection();
                g.setRequestMethod("GET");
                g.setRequestProperty("Authorization", "Bearer " + token);
                g.setRequestProperty("X-Requesting-Bank", teamId);
                int codeG = g.getResponseCode();
                long dt = System.currentTimeMillis() - t0;
                java.io.InputStream isg = (codeG >= 200 && codeG < 400) ? g.getInputStream() : g.getErrorStream();
                String respG = isg != null ? new String(isg.readAllBytes(), StandardCharsets.UTF_8) : "";
                boolean passGet = codeG >= 200 && codeG < 400;
                results.add(new EndpointResult("GET", getUrl, codeG, dt, passGet, "Lifecycle: get-after-create"));
                System.out.printf("[Lifecycle GET] %s -> %d (%s) %d ms\n    resp: %s%n", getUrl, codeG, passGet ? "OK" : "FAIL", dt, respG.length() > 300 ? respG.substring(0,300)+"..." : respG);
                g.disconnect();
            } catch (Exception e) {
                results.add(new EndpointResult("GET", baseUrl + "/account-consents/" + createdConsentId, -1, 0, false, "Lifecycle: get-after-create ERROR: " + e.getMessage()));
            }

            try {
                // DELETE
                String delUrl = baseUrl + "/account-consents/" + createdConsentId;
                long t0 = System.currentTimeMillis();
                HttpURLConnection d = (HttpURLConnection) new URL(delUrl).openConnection();
                d.setRequestMethod("DELETE");
                d.setRequestProperty("Authorization", "Bearer " + token);
                d.setRequestProperty("X-Requesting-Bank", teamId);
                int codeD = d.getResponseCode();
                long dt = System.currentTimeMillis() - t0;
                java.io.InputStream isd = (codeD >= 200 && codeD < 400) ? d.getInputStream() : d.getErrorStream();
                String respD = isd != null ? new String(isd.readAllBytes(), StandardCharsets.UTF_8) : "";
                boolean passDel = (codeD == 204 || (codeD >= 200 && codeD < 300));
                results.add(new EndpointResult("DELETE", delUrl, codeD, dt, passDel, "Lifecycle: delete"));
                System.out.printf("[Lifecycle DELETE] %s -> %d (%s) %d ms\n    resp: %s%n", delUrl, codeD, passDel ? "OK" : "FAIL", dt, respD.length() > 300 ? respD.substring(0,300)+"..." : respD);
                d.disconnect();
            } catch (Exception e) {
                results.add(new EndpointResult("DELETE", baseUrl + "/account-consents/" + createdConsentId, -1, 0, false, "Lifecycle: delete ERROR: " + e.getMessage()));
            }

            try {
                // GET after delete — ожидаем 404
                String get2Url = baseUrl + "/account-consents/" + createdConsentId;
                long t0 = System.currentTimeMillis();
                HttpURLConnection g2 = (HttpURLConnection) new URL(get2Url).openConnection();
                g2.setRequestMethod("GET");
                g2.setRequestProperty("Authorization", "Bearer " + token);
                g2.setRequestProperty("X-Requesting-Bank", teamId);
                int codeG2 = g2.getResponseCode();
                long dt = System.currentTimeMillis() - t0;
                java.io.InputStream isg2 = (codeG2 >= 200 && codeG2 < 400) ? g2.getInputStream() : g2.getErrorStream();
                String respG2 = isg2 != null ? new String(isg2.readAllBytes(), StandardCharsets.UTF_8) : "";
                boolean passGet2 = (codeG2 == 404 || codeG2 == 410);
                results.add(new EndpointResult("GET", get2Url, codeG2, dt, passGet2, "Lifecycle: get-after-delete"));
                System.out.printf("[Lifecycle GET-after-delete] %s -> %d (%s) %d ms\n    resp: %s%n", get2Url, codeG2, passGet2 ? "OK" : "unexpected", dt, respG2.length() > 300 ? respG2.substring(0,300)+"..." : respG2);
                g2.disconnect();
            } catch (Exception e) {
                results.add(new EndpointResult("GET", baseUrl + "/account-consents/" + createdConsentId, -1, 0, false, "Lifecycle: get-after-delete ERROR: " + e.getMessage()));
            }
        } else {
            System.out.println("⚠️ Lifecycle tests skipped because create did not return consent_id");
        }

        System.out.println("\n=== ✅ Consent tests finished ===");
        return results;
    }

}
