package com.hackathon.apiscanner.checks;

import okhttp3.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BrokenAuthCheck {

    private final OkHttpClient client = new OkHttpClient();

    public static class Result {
        public final String endpoint;
        public final String method;
        public final int statusCode;
        public final String severity;
        public final String description;

        public Result(String endpoint, String method, int statusCode, String severity, String description) {
            this.endpoint = endpoint;
            this.method = method;
            this.statusCode = statusCode;
            this.severity = severity;
            this.description = description;
        }

        @Override
        public String toString() {
            return "⚠️ [BrokenAuth] " + method + " " + endpoint + " → " + statusCode + " (" + severity + ") " + description;
        }
    }

    public List<Result> run(String baseUrl, List<String> endpoints) {
        List<Result> results = new ArrayList<>();

        for (String endpoint : endpoints) {
            String url = baseUrl + endpoint;

            Request request = new Request.Builder()
                    .url(url)
                    .build(); // ❌ без токена

            try (Response response = client.newCall(request).execute()) {
                int code = response.code();

                // если API отвечает без токена, значит уязвимость
                if (code == 200) {
                    results.add(new Result(endpoint, "GET", code, "HIGH",
                            "Эндпоинт доступен без авторизации"));
                }

            } catch (IOException e) {
                System.err.println("Ошибка при проверке " + endpoint + ": " + e.getMessage());
            }
        }

        return results;
    }
}
