package com.example.es.controller;

import com.example.es.dto.GeoSearchRequest;
import com.example.es.dto.PageResult;
import com.example.es.entity.Document;
import com.example.es.service.GeoSearchService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 地理距离搜索接口
 *
 * 根据传入的经纬度坐标，查询指定半径范围内的数据
 * 默认搜索半径：500米
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/geo")
public class GeoSearchController {

    @Autowired
    private GeoSearchService geoSearchService;

    /**
     * 根据经纬度查询附近数据（POST 方式，完整参数）
     *
     * 示例请求：
     * POST /api/v1/geo/nearby
     * {
     *   "index": "documents",
     *   "lat": 39.9042,
     *   "lon": 116.4074,
     *   "distance": 500,
     *   "keyword": "测试",
     *   "category": "tech",
     *   "status": 1,
     *   "pageSize": 20,
     *   "pageNum": 1,
     *   "sortOrder": "DISTANCE_ASC"
     * }
     */
    @PostMapping("/nearby")
    public ResponseEntity<PageResult<Document>> searchNearbyPost(@Valid @RequestBody GeoSearchRequest request) {
        log.info("地理距离搜索请求(POST): index={}, lat={}, lon={}, distance={}m",
                request.getIndex(), request.getLat(), request.getLon(), request.getDistance());
        PageResult<Document> result = geoSearchService.searchNearby(request);
        return ResponseEntity.ok(result);
    }

    /**
     * 根据经纬度查询附近数据（GET 方式，简化参数）
     *
     * 示例请求：
     * GET /api/v1/geo/nearby?index=documents&lat=39.9042&lon=116.4074&distance=500&pageSize=20
     */
    @GetMapping("/nearby")
    public ResponseEntity<PageResult<Document>> searchNearbyGet(
            @RequestParam String index,
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam(defaultValue = "500") Double distance,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "DISTANCE_ASC") GeoSearchRequest.SortOrder sortOrder) {

        GeoSearchRequest request = new GeoSearchRequest();
        request.setIndex(index);
        request.setLat(lat);
        request.setLon(lon);
        request.setDistance(distance);
        request.setKeyword(keyword);
        request.setCategory(category);
        request.setStatus(status);
        request.setPageSize(pageSize);
        request.setPageNum(pageNum);
        request.setSortOrder(sortOrder);

        log.info("地理距离搜索请求(GET): index={}, lat={}, lon={}, distance={}m",
                index, lat, lon, distance);
        PageResult<Document> result = geoSearchService.searchNearby(request);
        return ResponseEntity.ok(result);
    }
}
