package com.hackathon.apiscanner.report;

import com.hackathon.apiscanner.checks.BrokenAuthCheck;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class HtmlReportGenerator {

    public static class EndpointResult {
        public String method;
        public String url;
        public int code;
        public long timeMs;
        public boolean success;
        public String message;

        public EndpointResult(String method, String url, int code, long timeMs, boolean success, String message) {
            this.method = method;
            this.url = url;
            this.code = code;
            this.timeMs = timeMs;
            this.success = success;
            this.message = message;
        }

        public EndpointResult(String method, String url, int code, long timeMs, boolean success) {
            this(method, url, code, timeMs, success, "");
        }
    }

    public static void generateHtmlReport(
            List<EndpointResult> preResults,
            List<EndpointResult> results,
            int ok,
            int errors,
            int total,
            long totalMs,
            List<BrokenAuthCheck.Result> authIssues,
            List<EndpointResult> securityTestsResults
    )
    {
        StringBuilder html = new StringBuilder();
        html.append("""
                <!DOCTYPE html>
                <html lang="ru">
                <head>
                    <meta charset="UTF-8">
                    <title>API Scan Report</title>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
                    <style>
                        body { font-family: Arial, sans-serif; background: #fafafa; margin: 20px; color: #333; }
                        h1 { text-align: center; color: #2c3e50; }
                        table { width: 100%; border-collapse: collapse; margin-top: 20px; }
                        th, td { padding: 8px 12px; border: 1px solid #ccc; text-align: left; }
                        th { background: #2c3e50; color: white; }
                        tr:nth-child(even) { background: #f2f2f2; }
                        .ok { color: green; font-weight: bold; }
                        .fail { color: red; font-weight: bold; }
                        .summary { margin-top: 30px; padding: 10px; background: #eaf2f8; border-radius: 8px; }
                        .chart-container { width: 400px; margin: 20px auto; }
                        .small { color: #555; font-size: 13px; }
                    </style>
                </head>
                <body>
                <h1>📊 Отчёт о проверке API</h1>
                """);

        html.append("<div class='summary'>")
                .append("<p><b>Дата:</b> ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))).append("</p>")
                .append("<p><b>Всего эндпоинтов:</b> ").append(total).append("</p>")
                .append("<p><b>✅ Успешно:</b> ").append(ok).append(" | <b>❌ Ошибок:</b> ").append(errors).append("</p>")
                .append("<p><b>⏱ Время выполнения:</b> ").append(totalMs).append(" мс</p>")
                .append("</div>");

        // График


        // === Новая секция: подготовительные шаги ===
        if (preResults != null && !preResults.isEmpty()) {
            html.append("<h2>🧩 Выполненные подготовительные запросы</h2>");
            html.append("<table><tr><th>Метод</th><th>URL</th><th>Код</th><th>Статус</th><th>Описание</th><th>Время (мс)</th></tr>");
            for (EndpointResult r : preResults) {
                // 👇 Пропускаем технические записи (ACCESS_TOKEN_GLOBAL)
                if (r.url != null && r.url.contains("ACCESS_TOKEN_GLOBAL")) continue;

                boolean isOk = r.success || r.code == 204;
                html.append("<tr>")
                        .append("<td>").append(r.method).append("</td>")
                        .append("<td>").append(r.url).append("</td>")
                        .append("<td>").append(r.code).append("</td>")
                        .append("<td class='").append(isOk ? "ok" : "fail").append("'>")
                        .append(isOk ? "OK" : "FAIL").append("</td>")
                        .append("<td>").append(r.message == null ? "" : r.message).append("</td>")
                        .append("<td>").append(r.timeMs).append("</td>")
                        .append("</tr>");
            }
            html.append("</table>");
        }


        // === Основные результаты тестов ===
        html.append("<h2>📡 Проверка доступности API (Ping-тест эндпоинтов, OK/FAIL не более чем просто формальность)</h2>");

        html.append("<table><tr><th>Метод</th><th>URL</th><th>Код</th><th>Статус</th><th>Время (мс)</th></tr>");
        for (EndpointResult r : results) {
            html.append("<tr>")
                    .append("<td>").append(r.method).append("</td>")
                    .append("<td>").append(r.url).append("</td>")
                    .append("<td>").append(r.code).append("</td>")
                    .append("<td class='").append(r.success ? "ok" : "fail").append("'>")
                    .append(r.success ? "OK" : "FAIL").append("</td>")
                    .append("<td>").append(r.timeMs).append("</td>")
                    .append("</tr>");
        }
        html.append("</table>");

        // === Проверки безопасности ===
        html.append("<h2>🔐 Проверка безопасности</h2>");
        if (authIssues.isEmpty()) {
            html.append("<p class='ok'>Все эндпоинты защищены авторизацией.</p>");
        } else {
            html.append("<ul>");
            for (BrokenAuthCheck.Result r : authIssues) {
                html.append("<li class='fail'>").append(r.toString()).append("</li>");
            }
            html.append("</ul>");
        }

        // === Раздел: Тесты безопасности ===
        if (securityTestsResults != null && !securityTestsResults.isEmpty()) {
            html.append("<h2>🧪 Тесты безопасности (Consent API)</h2>");
            html.append("<table><tr><th>Тест</th><th>Метод</th><th>URL</th><th>Код</th><th>Результат</th><th>Время (мс)</th></tr>");
            for (EndpointResult r : securityTestsResults) {
                html.append("<tr>")
                        .append("<td>").append(r.message).append("</td>")
                        .append("<td>").append(r.method).append("</td>")
                        .append("<td>").append(r.url).append("</td>")
                        .append("<td>").append(r.code).append("</td>")
                        .append("<td class='").append(r.success ? "ok" : "fail").append("'>")
                        .append(r.success ? "OK" : "FAIL").append("</td>")
                        .append("<td>").append(r.timeMs).append("</td>")
                        .append("</tr>");
            }
            html.append("</table>");
        }



        html.append("</body></html>");

        try (FileWriter writer = new FileWriter("report.html", StandardCharsets.UTF_8)) {
            writer.write(html.toString());
            System.out.println("📄 HTML-отчёт сохранён: report.html");
        } catch (IOException e) {
            System.out.println("❌ Не удалось записать HTML-отчёт: " + e.getMessage());
        }
    }
}
