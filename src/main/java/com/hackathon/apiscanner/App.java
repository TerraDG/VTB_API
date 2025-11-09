package com.hackathon.apiscanner;

import com.hackathon.apiscanner.core.*;
import com.hackathon.apiscanner.checks.*;
import com.hackathon.apiscanner.report.*;
import com.hackathon.apiscanner.report.HtmlReportGenerator.EndpointResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class App {

    public static void main(String[] args) {
        String filePath = "openapi.json";
        String baseUrl = "https://vbank.open.bankingapi.ru";
        String clientId = "team178";
        String clientSecret = "0RwXPj7naBAD68elrQ2W8IAn8KmcQkCq";

        try {
            System.out.println("=== 🚀 API Security Scanner ===");

            // Получаем первичный токен
            String initialToken = AuthManager.getAccessToken(baseUrl, clientId, clientSecret);
            if (initialToken == null) {
                System.out.println("❌ Ошибка получения токена, завершение работы");
                return;
            }

            // ️Загружаем список эндпоинтов
            Map<String, List<String>> endpoints = ApiLoader.loadEndpoints(filePath);

            Instant start = Instant.now();

            // Загружаем endpoint-data.json
            EndpointDataProvider provider = new EndpointDataProvider("endpoint-data.json");

            // выполняем source-запросы и получаем данные
            Map<String, Map<String, Object>> resolved = provider.resolveAll(baseUrl, null);
            ApiTester.runConsentEndpointTests(baseUrl, initialToken, "team178");


            // Получаем сохранённый токен (если он есть)
            String authEndpointKey = "/auth/bank-token";
            String tokenFromProvider = provider.getSavedValue(authEndpointKey, "/access_token");

            String token = tokenFromProvider != null ? tokenFromProvider : initialToken;
            if (tokenFromProvider != null) {
                System.out.println("🔑 Токен успешно получен и сохранён (preview): " +
                        tokenFromProvider.substring(0, 8) + "...");
            }

            // Проверка авторизации (Broken Auth)
            BrokenAuthCheck brokenAuthCheck = new BrokenAuthCheck();
            List<BrokenAuthCheck.Result> brokenAuthResults =
                    brokenAuthCheck.run(baseUrl, endpoints.keySet().stream().toList());

            // Запускаем тестирование всех эндпоинтов (из OpenAPI)
            List<EndpointResult> generalResults = ApiTester.testEndpoints(baseUrl, initialToken, endpoints, resolved);

            // Запускаем тесты согласий (специальные тесты)
            List<EndpointResult> consentResults = ApiTester.runConsentEndpointTests(baseUrl, initialToken, clientId);

            // Объединяем результаты
            List<EndpointResult> results = new ArrayList<>();
            results.addAll(generalResults);
            results.addAll(consentResults);


            // Считаем итоги
            long total = Duration.between(start, Instant.now()).toMillis();
            int ok = (int) results.stream().filter(r -> r.success).count();
            int errors = results.size() - ok;

            // Пустой список для устаревших "securityResults"
            List<com.hackathon.apiscanner.checks.BrokenAuthCheck.Result> securityResults = new ArrayList<>();

            List<EndpointResult> consentSecurityTests = ApiTester.runConsentEndpointTests(baseUrl, initialToken, "team178");

            // Генерация отчёта
            HtmlReportGenerator.generateHtmlReport(
                    List.of(),
                    results,
                    ok, errors, results.size(), total,
                    brokenAuthResults,
                    consentSecurityTests
            );

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
