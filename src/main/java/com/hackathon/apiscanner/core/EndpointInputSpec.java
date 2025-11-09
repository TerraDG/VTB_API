package com.hackathon.apiscanner.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Описание входных данных для эндпоинта из endpoint-data.json
 */
public class EndpointInputSpec {
    public String method;
    public Map<String, SpecValue> headers;
    public Map<String, SpecValue> pathParams;
    public Map<String, SpecValue> query;          // ✅ добавлено
    public Map<String, SpecValue> body;
    public Map<String, SpecValue> saveResponse;   // ✅ теперь это тоже SpecValue

    public static class SpecValue {
        public JsonNode value;       // буквальное значение
        public String from;          // имя эндпоинта-источника
        public String jsonPointer;   // JSON Pointer из ответа
    }
}
