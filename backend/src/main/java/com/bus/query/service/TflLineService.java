package com.bus.query.service;

import com.bus.query.config.TflProperties;
import com.bus.query.model.ArrivalPredictionDto;
import com.bus.query.model.LatLngDto;
import com.bus.query.model.LineDirectionDto;
import com.bus.query.model.LineMapResponse;
import com.bus.query.model.LineSearchItem;
import com.bus.query.model.StopMarkerDto;
import com.bus.query.model.VehicleLegDto;
import com.bus.query.model.VehiclePositionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class TflLineService {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final RestClient restClient;
    private final TflProperties tflProperties;
    private final ObjectMapper objectMapper;

    public TflLineService(TflProperties tflProperties, ObjectMapper objectMapper) {
        this.tflProperties = tflProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(tflProperties.baseUrl())
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().set("Accept", "application/json");
                    return execution.execute(request, body);
                })
                .build();
    }

    public List<LineSearchItem> searchLines(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        String lineId = extractLineId(query);
        if (!StringUtils.hasText(lineId)) {
            return List.of();
        }

        return List.of(buildLineSearchItem(lineId));
    }

    public LineMapResponse getLineMap(String lineId) {
        String normalizedLineId = extractLineId(lineId);
        if (!StringUtils.hasText(normalizedLineId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "线路号不能为空");
        }

        JsonNode routeSequence = getJson(uriBuilder("/Line/{id}/Route/Sequence/all").build(normalizedLineId));
        JsonNode arrivals = getJson(uriBuilder("/Line/{id}/Arrivals").build(normalizedLineId));
        LineSearchItem lineItem = buildLineSearchItem(normalizedLineId);

        LinkedHashMap<String, StopMarkerDto> stops = extractStops(routeSequence);
        Map<String, List<ArrivalPredictionDto>> arrivalMap = extractArrivals(arrivals);
        List<LineDirectionDto> directions = extractDirections(routeSequence, arrivalMap);
        Map<String, List<ArrivalPredictionDto>> augmentedArrivalMap = buildAugmentedArrivalMap(arrivals, directions, arrivalMap);

        LinkedHashMap<String, StopMarkerDto> mergedStops = new LinkedHashMap<>();
        for (StopMarkerDto stop : stops.values()) {
            List<ArrivalPredictionDto> predictions = augmentedArrivalMap.getOrDefault(stop.id(), List.of());
            mergedStops.put(stop.id(), new StopMarkerDto(stop.id(), stop.name(), stop.lat(), stop.lng(), predictions));
        }
        directions = applyArrivalsToDirections(directions, augmentedArrivalMap);
        if (directions.isEmpty()) {
            directions = List.of(new LineDirectionDto(
                    "default",
                    "全线站点",
                    mergedStops.values().stream().reduce((first, second) -> second).map(StopMarkerDto::name).orElse("终点站"),
                    new ArrayList<>(mergedStops.values())
            ));
        }

        List<LatLngDto> routePath = extractLineStrings(routeSequence);
        if (routePath.isEmpty()) {
            routePath = mergedStops.values().stream()
                    .map(stop -> new LatLngDto(stop.lat(), stop.lng()))
                    .toList();
        }

        List<VehiclePositionDto> vehicles = extractVehiclePositions(arrivals, mergedStops, routePath);
        String vehicleStatus = "ok";
        String vehicleMessage = "已同步最新到站预测数据，可在侧边栏查看各站最近两辆车的预计到站时间";

        if (!arrivals.isArray() || arrivals.isEmpty()) {
            vehicleStatus = "empty";
            vehicleMessage = "TfL 当前未返回这条线路的到站预测数据";
        } else if (vehicles.isEmpty()) {
            vehicleStatus = "unresolved";
            vehicleMessage = "线路和站点已加载，但当前缺少可用的到站预测明细";
        }

        return new LineMapResponse(
                lineItem.id(),
                lineItem.name(),
                lineItem.displayName(),
                lineItem.city(),
                routePath,
                new ArrayList<>(mergedStops.values()),
                directions,
                vehicles,
                vehicleStatus,
                vehicleMessage
        );
    }

    private JsonNode getJson(URI uri) {
        try {
            String raw = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            return objectMapper.readTree(Objects.requireNonNullElse(raw, "[]"));
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "调用 TfL 接口失败，请检查网络或 TfL API Key 配置",
                    exception
            );
        }
    }

    private UriComponentsBuilder uriBuilder(String path) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(tflProperties.baseUrl()).path(path);
        if (StringUtils.hasText(tflProperties.appId())) {
            builder.queryParam("app_id", tflProperties.appId());
        }
        if (StringUtils.hasText(tflProperties.appKey())) {
            builder.queryParam("app_key", tflProperties.appKey());
        }
        return builder;
    }

    private LinkedHashMap<String, StopMarkerDto> extractStops(JsonNode routeSequence) {
        LinkedHashMap<String, StopMarkerDto> stops = new LinkedHashMap<>();
        JsonNode sequences = routeSequence.path("stopPointSequences");
        if (!sequences.isArray()) {
            return stops;
        }

        for (JsonNode sequence : sequences) {
            JsonNode sequenceStops = sequence.path("stopPoint");
            if (!sequenceStops.isArray()) {
                sequenceStops = sequence.path("stopPoints");
            }

            if (!sequenceStops.isArray()) {
                continue;
            }

            for (JsonNode stopNode : sequenceStops) {
                String id = resolveStopId(stopNode);
                if (!StringUtils.hasText(id) || stops.containsKey(id)) {
                    continue;
                }

                double lat = stopNode.path("lat").asDouble(Double.NaN);
                double lng = stopNode.path("lon").asDouble(Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) {
                    continue;
                }

                stops.put(id, new StopMarkerDto(
                        id,
                        textValue(stopNode, "name"),
                        lat,
                        lng,
                        List.of()
                ));
            }
        }
        return stops;
    }

    private List<LineDirectionDto> extractDirections(
            JsonNode routeSequence,
            Map<String, List<ArrivalPredictionDto>> arrivalMap
    ) {
        JsonNode sequences = routeSequence.path("stopPointSequences");
        if (!sequences.isArray()) {
            return List.of();
        }

        List<LineDirectionDto> directions = new ArrayList<>();
        int sequenceIndex = 0;

        for (JsonNode sequence : sequences) {
            JsonNode sequenceStops = sequence.path("stopPoint");
            if (!sequenceStops.isArray()) {
                sequenceStops = sequence.path("stopPoints");
            }
            if (!sequenceStops.isArray()) {
                continue;
            }

            List<StopMarkerDto> orderedStops = new ArrayList<>();
            for (JsonNode stopNode : sequenceStops) {
                String id = resolveStopId(stopNode);
                if (!StringUtils.hasText(id)) {
                    continue;
                }

                double lat = stopNode.path("lat").asDouble(Double.NaN);
                double lng = stopNode.path("lon").asDouble(Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) {
                    continue;
                }

                orderedStops.add(new StopMarkerDto(
                        id,
                        textValue(stopNode, "name"),
                        lat,
                        lng,
                        arrivalMap.getOrDefault(id, List.of())
                ));
            }

            if (orderedStops.isEmpty()) {
                continue;
            }

            String destinationName = firstNonBlank(
                    textValue(sequence, "destination"),
                    textValue(sequence, "name"),
                    orderedStops.get(orderedStops.size() - 1).name()
            );

            directions.add(new LineDirectionDto(
                    "direction-" + sequenceIndex,
                    "开往 " + destinationName,
                    destinationName,
                    orderedStops
            ));
            sequenceIndex += 1;
        }

        return directions;
    }

    private List<LineDirectionDto> applyArrivalsToDirections(
            List<LineDirectionDto> directions,
            Map<String, List<ArrivalPredictionDto>> arrivalMap
    ) {
        return directions.stream()
                .map(direction -> new LineDirectionDto(
                        direction.id(),
                        direction.label(),
                        direction.destinationName(),
                        direction.stops().stream()
                                .map(stop -> new StopMarkerDto(
                                        stop.id(),
                                        stop.name(),
                                        stop.lat(),
                                        stop.lng(),
                                        arrivalMap.getOrDefault(stop.id(), List.of())
                                ))
                                .toList()
                ))
                .toList();
    }

    private Map<String, List<ArrivalPredictionDto>> buildAugmentedArrivalMap(
            JsonNode arrivals,
            List<LineDirectionDto> directions,
            Map<String, List<ArrivalPredictionDto>> baseArrivalMap
    ) {
        Map<String, List<ArrivalPredictionDto>> merged = new LinkedHashMap<>();
        baseArrivalMap.forEach((stopId, predictions) -> merged.put(stopId, new ArrayList<>(predictions)));

        if (!arrivals.isArray()) {
            return sortAndDedupeArrivalMap(merged);
        }

        for (LineDirectionDto direction : directions) {
            List<StopMarkerDto> stops = direction.stops();
            if (stops.isEmpty()) {
                continue;
            }

            Map<String, Integer> stopOrder = new HashMap<>();
            for (int index = 0; index < stops.size(); index++) {
                stopOrder.put(stops.get(index).id(), index);
            }

            Map<String, Integer> segmentDurationLookup = buildSegmentDurationLookup(arrivals, stopOrder);
            Map<String, List<JsonNode>> vehiclePredictions = new LinkedHashMap<>();

            for (JsonNode node : arrivals) {
                String vehicleId = textValue(node, "vehicleId");
                String stopId = textValue(node, "naptanId");
                if (!StringUtils.hasText(vehicleId) || !stopOrder.containsKey(stopId)) {
                    continue;
                }
                vehiclePredictions.computeIfAbsent(vehicleId, ignored -> new ArrayList<>()).add(node);
            }

            for (List<JsonNode> rawPredictions : vehiclePredictions.values()) {
                List<JsonNode> predictions = sanitizePredictions(rawPredictions, stopOrder);
                if (predictions.isEmpty()) {
                    continue;
                }

                JsonNode anchorPrediction = predictions.get(0);
                String destinationName = textValue(anchorPrediction, "destinationName");
                int anchorSeconds = anchorPrediction.path("timeToStation").asInt(Integer.MAX_VALUE);
                String anchorStopId = textValue(anchorPrediction, "naptanId");
                Integer anchorOrder = stopOrder.get(anchorStopId);
                if (anchorOrder == null || anchorSeconds == Integer.MAX_VALUE) {
                    continue;
                }

                String vehicleId = textValue(anchorPrediction, "vehicleId");
                int cumulativeSeconds = anchorSeconds;
                for (int targetOrder = anchorOrder + 1; targetOrder < stops.size(); targetOrder++) {
                    String previousStopId = stops.get(targetOrder - 1).id();
                    String targetStopId = stops.get(targetOrder).id();
                    String segmentKey = previousStopId + "->" + targetStopId;
                    cumulativeSeconds += Math.max(60, segmentDurationLookup.getOrDefault(segmentKey, 180));

                    merged.computeIfAbsent(targetStopId, ignored -> new ArrayList<>()).add(new ArrivalPredictionDto(
                            destinationName,
                            null,
                            cumulativeSeconds,
                            vehicleId,
                            true
                    ));
                }
            }
        }

        return sortAndDedupeArrivalMap(merged);
    }

    private Map<String, List<ArrivalPredictionDto>> sortAndDedupeArrivalMap(Map<String, List<ArrivalPredictionDto>> arrivalMap) {
        Map<String, List<ArrivalPredictionDto>> normalized = new LinkedHashMap<>();

        arrivalMap.forEach((stopId, predictions) -> {
            Map<String, ArrivalPredictionDto> deduped = new LinkedHashMap<>();
            predictions.stream()
                    .sorted(Comparator.comparingInt(prediction ->
                            prediction.timeToStationSeconds() == null ? Integer.MAX_VALUE : prediction.timeToStationSeconds()
                    ))
                    .forEach(prediction -> {
                        String key = prediction.vehicleId() + "|" + prediction.destinationName();
                        ArrivalPredictionDto existing = deduped.get(key);
                        if (existing == null) {
                            deduped.put(key, prediction);
                            return;
                        }

                        int existingSeconds = existing.timeToStationSeconds() == null ? Integer.MAX_VALUE : existing.timeToStationSeconds();
                        int nextSeconds = prediction.timeToStationSeconds() == null ? Integer.MAX_VALUE : prediction.timeToStationSeconds();
                        if (nextSeconds < existingSeconds || (nextSeconds == existingSeconds && !prediction.inferred() && existing.inferred())) {
                            deduped.put(key, prediction);
                        }
                    });

            normalized.put(stopId, deduped.values().stream()
                    .sorted(Comparator.comparingInt(prediction ->
                            prediction.timeToStationSeconds() == null ? Integer.MAX_VALUE : prediction.timeToStationSeconds()
                    ))
                    .toList());
        });

        return normalized;
    }

    private Map<String, List<ArrivalPredictionDto>> extractArrivals(JsonNode arrivals) {
        if (!arrivals.isArray()) {
            return Map.of();
        }

        Map<String, List<ArrivalPredictionDto>> grouped = new LinkedHashMap<>();
        List<JsonNode> sortedItems = new ArrayList<>();
        arrivals.forEach(sortedItems::add);
        sortedItems.sort(Comparator.comparingInt(node -> node.path("timeToStation").asInt(Integer.MAX_VALUE)));

        for (JsonNode node : sortedItems) {
            String stopId = textValue(node, "naptanId");
            if (!StringUtils.hasText(stopId)) {
                continue;
            }

            grouped.computeIfAbsent(stopId, ignored -> new ArrayList<>());
            grouped.get(stopId).add(new ArrivalPredictionDto(
                    textValue(node, "destinationName"),
                    textValue(node, "expectedArrival"),
                    node.path("timeToStation").isMissingNode() ? null : node.path("timeToStation").asInt(),
                    textValue(node, "vehicleId"),
                    false
            ));
        }

        return grouped;
    }

    private List<VehiclePositionDto> extractVehiclePositions(
            JsonNode arrivals,
            LinkedHashMap<String, StopMarkerDto> orderedStops,
            List<LatLngDto> routePath
    ) {
        if (!arrivals.isArray()) {
            return List.of();
        }

        List<StopMarkerDto> stopList = new ArrayList<>(orderedStops.values());
        Map<String, Integer> stopOrder = new HashMap<>();
        for (int index = 0; index < stopList.size(); index++) {
            stopOrder.put(stopList.get(index).id(), index);
        }

        Map<String, Integer> routeIndexByStopId = mapStopToRouteIndex(stopList, routePath);
        Map<String, List<JsonNode>> vehiclePredictions = new LinkedHashMap<>();
        Map<String, Integer> segmentDurationLookup = buildSegmentDurationLookup(arrivals, stopOrder);

        for (JsonNode node : arrivals) {
            String vehicleId = textValue(node, "vehicleId");
            String stopId = textValue(node, "naptanId");
            if (!StringUtils.hasText(vehicleId) || !stopOrder.containsKey(stopId)) {
                continue;
            }

            vehiclePredictions.computeIfAbsent(vehicleId, ignored -> new ArrayList<>()).add(node);
        }

        List<VehiclePositionDto> vehicles = new ArrayList<>();
        for (Map.Entry<String, List<JsonNode>> entry : vehiclePredictions.entrySet()) {
            List<JsonNode> predictions = sanitizePredictions(entry.getValue(), stopOrder);
            if (predictions.isEmpty()) {
                continue;
            }

            JsonNode nextPrediction = predictions.get(0);
            String nextStopId = textValue(nextPrediction, "naptanId");
            Integer nextStopOrder = stopOrder.get(nextStopId);
            if (nextStopOrder == null) {
                continue;
            }

            int previousStopOrder = Math.max(0, nextStopOrder - 1);
            StopMarkerDto previousStop = stopList.get(previousStopOrder);
            StopMarkerDto nextStop = stopList.get(nextStopOrder);

            int segmentSeconds = estimateIncomingSegmentSeconds(stopList, previousStopOrder, nextStopOrder, segmentDurationLookup);
            int timeToNextStop = nextPrediction.path("timeToStation").asInt(segmentSeconds);
            double progress = 1d - Math.min(1d, Math.max(0d, timeToNextStop / (double) segmentSeconds));

            LatLngDto position = interpolateOnRoute(
                    routePath,
                    routeIndexByStopId.getOrDefault(previousStop.id(), 0),
                    routeIndexByStopId.getOrDefault(nextStop.id(), Math.max(routePath.size() - 1, 0)),
                    progress,
                    previousStop,
                    nextStop
            );

            List<VehicleLegDto> travelPlan = buildTravelPlan(stopList, predictions, stopOrder, segmentDurationLookup);

            vehicles.add(new VehiclePositionDto(
                    entry.getKey(),
                    textValue(nextPrediction, "destinationName"),
                    textValue(nextPrediction, "currentLocation"),
                    nextStop.id(),
                    nextStop.name(),
                    timeToNextStop,
                    nextPrediction.path("bearing").isNumber() ? nextPrediction.path("bearing").asDouble() : null,
                    position.lat(),
                    position.lng(),
                    travelPlan
            ));
        }

        return vehicles;
    }

    private List<JsonNode> sanitizePredictions(List<JsonNode> rawPredictions, Map<String, Integer> stopOrder) {
        List<JsonNode> sorted = new ArrayList<>(rawPredictions);
        sorted.sort(Comparator.comparingInt(node -> node.path("timeToStation").asInt(Integer.MAX_VALUE)));

        List<JsonNode> cleaned = new ArrayList<>();
        int lastOrder = -1;
        for (JsonNode node : sorted) {
            String stopId = textValue(node, "naptanId");
            Integer currentOrder = stopOrder.get(stopId);
            if (currentOrder == null) {
                continue;
            }
            if (currentOrder <= lastOrder) {
                continue;
            }
            cleaned.add(node);
            lastOrder = currentOrder;
        }
        return cleaned;
    }

    private List<LatLngDto> extractLineStrings(JsonNode routeSequence) {
        List<LatLngDto> path = new ArrayList<>();
        JsonNode lineStrings = routeSequence.path("lineStrings");
        if (!lineStrings.isArray()) {
            return path;
        }

        for (JsonNode lineStringNode : lineStrings) {
            String lineString = lineStringNode.asText("");
            if (!StringUtils.hasText(lineString)) {
                continue;
            }

            List<Double> numbers = parseNumbers(lineString);
            if (numbers.size() < 4 || numbers.size() % 2 != 0) {
                continue;
            }

            boolean latLngOrder = isLikelyLatLng(numbers);
            for (int index = 0; index < numbers.size(); index += 2) {
                double first = numbers.get(index);
                double second = numbers.get(index + 1);
                double lat = latLngOrder ? first : second;
                double lng = latLngOrder ? second : first;

                if (Math.abs(lat) > 90 || Math.abs(lng) > 180) {
                    continue;
                }

                path.add(new LatLngDto(lat, lng));
            }
        }

        return deduplicatePath(path);
    }

    private Map<String, Integer> mapStopToRouteIndex(List<StopMarkerDto> stops, List<LatLngDto> routePath) {
        Map<String, Integer> mapping = new HashMap<>();
        if (routePath.isEmpty()) {
            return mapping;
        }

        int lastMatchedIndex = 0;
        for (StopMarkerDto stop : stops) {
            int bestIndex = lastMatchedIndex;
            double bestDistance = Double.MAX_VALUE;
            for (int index = lastMatchedIndex; index < routePath.size(); index++) {
                LatLngDto point = routePath.get(index);
                double distance = distanceSquared(stop.lat(), stop.lng(), point.lat(), point.lng());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = index;
                }
            }

            mapping.put(stop.id(), bestIndex);
            lastMatchedIndex = bestIndex;
        }
        return mapping;
    }

    private Map<String, Integer> buildSegmentDurationLookup(JsonNode arrivals, Map<String, Integer> stopOrder) {
        if (!arrivals.isArray()) {
            return Map.of();
        }

        Map<String, List<JsonNode>> grouped = new HashMap<>();
        for (JsonNode node : arrivals) {
            String vehicleId = textValue(node, "vehicleId");
            String stopId = textValue(node, "naptanId");
            if (!StringUtils.hasText(vehicleId) || !stopOrder.containsKey(stopId)) {
                continue;
            }
            grouped.computeIfAbsent(vehicleId, ignored -> new ArrayList<>()).add(node);
        }

        Map<String, List<Integer>> durations = new HashMap<>();
        for (List<JsonNode> predictions : grouped.values()) {
            predictions.sort(Comparator.comparingInt(node -> stopOrder.getOrDefault(textValue(node, "naptanId"), Integer.MAX_VALUE)));
            for (int index = 1; index < predictions.size(); index++) {
                JsonNode previous = predictions.get(index - 1);
                JsonNode current = predictions.get(index);
                int duration = current.path("timeToStation").asInt(0) - previous.path("timeToStation").asInt(0);
                if (duration <= 0) {
                    continue;
                }
                String key = textValue(previous, "naptanId") + "->" + textValue(current, "naptanId");
                durations.computeIfAbsent(key, ignored -> new ArrayList<>()).add(duration);
            }
        }

        return durations.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> (int) Math.round(entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(180))
        ));
    }

    private int estimateIncomingSegmentSeconds(
            List<StopMarkerDto> stopList,
            int previousStopOrder,
            int nextStopOrder,
            Map<String, Integer> segmentDurationLookup
    ) {
        if (nextStopOrder <= previousStopOrder || nextStopOrder >= stopList.size()) {
            return 180;
        }

        String key = stopList.get(previousStopOrder).id() + "->" + stopList.get(nextStopOrder).id();
        return Math.max(60, segmentDurationLookup.getOrDefault(key, 180));
    }

    private List<VehicleLegDto> buildTravelPlan(
            List<StopMarkerDto> stopList,
            List<JsonNode> predictions,
            Map<String, Integer> stopOrder,
            Map<String, Integer> segmentDurationLookup
    ) {
        List<VehicleLegDto> plan = new ArrayList<>();
        if (predictions.isEmpty()) {
            return plan;
        }

        JsonNode firstPrediction = predictions.get(0);
        String firstStopId = textValue(firstPrediction, "naptanId");
        Integer firstStopOrder = stopOrder.get(firstStopId);
        if (firstStopOrder == null) {
            return plan;
        }

        int previousStopOrder = Math.max(0, firstStopOrder - 1);
        StopMarkerDto previousStop = stopList.get(previousStopOrder);
        StopMarkerDto firstStop = stopList.get(firstStopOrder);
        plan.add(new VehicleLegDto(
                previousStop.id(),
                previousStop.name(),
                firstStop.id(),
                firstStop.name(),
                Math.max(15, firstPrediction.path("timeToStation").asInt(180))
        ));

        for (int index = 1; index < predictions.size(); index++) {
            JsonNode previous = predictions.get(index - 1);
            JsonNode current = predictions.get(index);
            String previousStopId = textValue(previous, "naptanId");
            String currentStopId = textValue(current, "naptanId");
            Integer previousOrder = stopOrder.get(previousStopId);
            Integer currentOrder = stopOrder.get(currentStopId);
            if (previousOrder == null || currentOrder == null || currentOrder <= previousOrder) {
                continue;
            }

            int duration = current.path("timeToStation").asInt(0) - previous.path("timeToStation").asInt(0);
            if (duration <= 0) {
                duration = segmentDurationLookup.getOrDefault(previousStopId + "->" + currentStopId, 180);
            }

            plan.add(new VehicleLegDto(
                    previousStopId,
                    stopList.get(previousOrder).name(),
                    currentStopId,
                    stopList.get(currentOrder).name(),
                    Math.max(15, duration)
            ));
        }

        return plan;
    }

    private LatLngDto interpolateOnRoute(
            List<LatLngDto> routePath,
            int startIndex,
            int endIndex,
            double progress,
            StopMarkerDto fallbackStart,
            StopMarkerDto fallbackEnd
    ) {
        if (routePath.isEmpty()) {
            return interpolateDirect(fallbackStart.lat(), fallbackStart.lng(), fallbackEnd.lat(), fallbackEnd.lng(), progress);
        }

        int safeStart = Math.max(0, Math.min(startIndex, routePath.size() - 1));
        int safeEnd = Math.max(0, Math.min(endIndex, routePath.size() - 1));
        if (safeEnd <= safeStart) {
            return interpolateDirect(fallbackStart.lat(), fallbackStart.lng(), fallbackEnd.lat(), fallbackEnd.lng(), progress);
        }

        List<LatLngDto> segment = routePath.subList(safeStart, safeEnd + 1);
        if (segment.size() < 2) {
            return interpolateDirect(fallbackStart.lat(), fallbackStart.lng(), fallbackEnd.lat(), fallbackEnd.lng(), progress);
        }

        double totalDistance = 0d;
        List<Double> cumulativeDistances = new ArrayList<>();
        cumulativeDistances.add(0d);
        for (int index = 1; index < segment.size(); index++) {
            totalDistance += distance(segment.get(index - 1), segment.get(index));
            cumulativeDistances.add(totalDistance);
        }

        if (totalDistance <= 0d) {
            return interpolateDirect(fallbackStart.lat(), fallbackStart.lng(), fallbackEnd.lat(), fallbackEnd.lng(), progress);
        }

        double targetDistance = totalDistance * progress;
        for (int index = 1; index < segment.size(); index++) {
            double previousDistance = cumulativeDistances.get(index - 1);
            double currentDistance = cumulativeDistances.get(index);
            if (targetDistance <= currentDistance) {
                double localProgress = (targetDistance - previousDistance) / (currentDistance - previousDistance);
                LatLngDto start = segment.get(index - 1);
                LatLngDto end = segment.get(index);
                return interpolateDirect(start.lat(), start.lng(), end.lat(), end.lng(), localProgress);
            }
        }

        return segment.get(segment.size() - 1);
    }

    private LatLngDto interpolateDirect(double startLat, double startLng, double endLat, double endLng, double progress) {
        double clamped = Math.max(0d, Math.min(1d, progress));
        return new LatLngDto(
                startLat + (endLat - startLat) * clamped,
                startLng + (endLng - startLng) * clamped
        );
    }

    private double distance(LatLngDto first, LatLngDto second) {
        return Math.sqrt(distanceSquared(first.lat(), first.lng(), second.lat(), second.lng()));
    }

    private double distanceSquared(double firstLat, double firstLng, double secondLat, double secondLng) {
        double latDiff = firstLat - secondLat;
        double lngDiff = firstLng - secondLng;
        return latDiff * latDiff + lngDiff * lngDiff;
    }

    private List<Double> parseNumbers(String input) {
        List<Double> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(input);
        while (matcher.find()) {
            numbers.add(Double.parseDouble(matcher.group()));
        }
        return numbers;
    }

    private boolean isLikelyLatLng(List<Double> numbers) {
        if (numbers.size() < 2) {
            return true;
        }

        double first = numbers.get(0);
        double second = numbers.get(1);
        boolean firstLooksLat = Math.abs(first) > 1 && Math.abs(first) <= 90;
        boolean secondLooksLng = Math.abs(second) <= 30;
        return firstLooksLat && secondLooksLng;
    }

    private List<LatLngDto> deduplicatePath(List<LatLngDto> source) {
        List<LatLngDto> deduplicated = new ArrayList<>();
        LatLngDto previous = null;
        for (LatLngDto point : source) {
            if (previous == null
                    || Double.compare(previous.lat(), point.lat()) != 0
                    || Double.compare(previous.lng(), point.lng()) != 0) {
                deduplicated.add(point);
                previous = point;
            }
        }
        return deduplicated;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replace("公交", "")
                        .replace("路", "")
                        .replace("bus", "")
                        .replaceAll("\\s+", "");
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String resolveStopId(JsonNode stopNode) {
        return firstNonBlank(
                textValue(stopNode, "naptanId"),
                textValue(stopNode, "id")
        );
    }

    private LineSearchItem buildLineSearchItem(String lineId) {
        String normalized = extractLineId(lineId);
        return new LineSearchItem(
                normalized,
                normalized,
                normalized + "路公交",
                "伦敦",
                "bus"
        );
    }

    private String extractLineId(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }

        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("[A-Z]*\\d+[A-Z]*").matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        return trimmed.replaceAll("\\s+", "");
    }
}
