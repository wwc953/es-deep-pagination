# ES 深度分页查询接口

基于 Spring Boot 3 和 Elasticsearch 9.4 的深度分页查询解决方案。

## 特性

支持三种分页模式：

| 模式 | 适用场景 | 优点 | 缺点 |
|------|---------|------|------|
| **FROM** | 浅分页（from < 10000） | 简单直接 | 性能随from增大而下降 |
| **SCROLL** | 全量导出 | 高效遍历全部数据 | 实时性差，快照数据 |
| **SEARCH_AFTER + PIT** | 深度分页（推荐） | 实时性好，性能稳定 | 需要客户端维护状态 |

## 技术栈

- Spring Boot 3.3.1
- Elasticsearch 9.4
- Java 17
- Maven

## 快速开始

### 1. 启动Elasticsearch

```bash
docker run -d --name es9 \
  -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  docker.elastic.co/elasticsearch/elasticsearch:8.18.1

```

### 2. 启动应用

```bash
mvn spring-boot:run
```

### 3. 初始化测试数据

```bash
# 创建索引并插入10000条测试数据
curl -X POST http://localhost:8080/api/v1/index/create/documents
```

> 注意：需要在 `DataInitializer.java` 中取消 `@Component` 注解的注释来启用自动初始化。

## API 接口

### 1. 深度分页查询（通用）

**POST** `/api/v1/pagination/search`

```json
{
  "index": "documents",
  "keyword": "测试",
  "category": "tech",
  "status": 1,
  "pageSize": 20,
  "sortField": "createTime",
  "sortOrder": "DESC",
  "mode": "SEARCH_AFTER"
}
```

### 2. 第一页查询（简化版）

**GET** `/api/v1/pagination/search-first?index=documents&keyword=测试&pageSize=20`

### 3. 下一页查询（Search After模式）

**POST** `/api/v1/pagination/search-next`

```json
{
  "index": "documents",
  "pitId": "46ToAwMDaWR...",
  "searchAfter": [1704067200000, "document-100"],
  "pageSize": 20,
  "mode": "SEARCH_AFTER"
}
```

### 4. 继续Scroll滚动

**POST** `/api/v1/pagination/scroll-next`

```json
{
  "scrollId": "DXF1ZXJ5QW5...",
  "index": "documents",
  "pageSize": 20,
  "mode": "SCROLL"
}
```

### 5. 清理上下文

```bash
# 清理Scroll
curl -X DELETE http://localhost:8080/api/v1/pagination/scroll/{scrollId}

# 清理PIT
curl -X DELETE http://localhost:8080/api/v1/pagination/pit/{pitId}
```

## 响应示例

```json
{
  "data": [
    {
      "id": "document-1",
      "title": "测试文档标题-1",
      "content": "这是第 1 条测试文档的内容...",
      "category": "tech",
      "author": "张三",
      "status": 1,
      "createTime": "2024-01-01T00:00:00"
    }
  ],
  "total": 10000,
  "pageNum": 1,
  "pageSize": 20,
  "hasNext": true,
  "pitId": "46ToAwMDaWR...",
  "searchAfter": [1704067200000, "document-20"],
  "costTime": 15
}
```

## 深度分页最佳实践

### 1. 前端无限滚动（推荐）

```javascript
// 首次查询
const response = await fetch('/api/v1/pagination/search-first?index=documents&pageSize=20');
const result = await response.json();

// 滚动到底部时查询下一页
async function loadMore() {
  if (!result.hasNext) return;

  const response = await fetch('/api/v1/pagination/search-next', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      index: 'documents',
      pitId: result.pitId,
      searchAfter: result.searchAfter,
      pageSize: 20,
      mode: 'SEARCH_AFTER'
    })
  });

  const nextResult = await response.json();
  result.data.push(...nextResult.data);
  result.hasNext = result.hasNext;
  result.pitId = nextResult.pitId;
  result.searchAfter = nextResult.searchAfter;
}
```

### 2. 数据导出

```javascript
// 使用Scroll模式导出所有数据
async function exportAll() {
  let allData = [];
  let scrollId = null;
  let hasMore = true;

  // 首次查询
  const response = await fetch('/api/v1/pagination/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      index: 'documents',
      pageSize: 1000,
      mode: 'SCROLL'
    })
  });

  let result = await response.json();
  allData.push(...result.data);
  scrollId = result.scrollId;

  // 继续滚动
  while (hasMore && result.data.length > 0) {
    const nextResponse = await fetch('/api/v1/pagination/scroll-next', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        index: 'documents',
        scrollId: scrollId,
        pageSize: 1000,
        mode: 'SCROLL'
      })
    });

    result = await nextResponse.json();
    allData.push(...result.data);
    scrollId = result.scrollId;
    hasMore = result.hasNext;
  }

  // 清理Scroll上下文
  await fetch(`/api/v1/pagination/scroll/${scrollId}`, { method: 'DELETE' });

  return allData;
}
```

## 配置说明

在 `application.yml` 中配置：

```yaml
deep-pagination:
  max-page-size: 10000        # 最大分页大小
  scroll-keep-alive: 5       # Scroll上下文保持时间（分钟）
  pit-keep-alive: 2          # PIT保持时间（分钟）
  default-page-size: 20       # 默认每页大小
```

## 性能对比

| 分页方式 | 第1页 | 第100页 | 第1000页 | 第10000页 |
|---------|-------|---------|----------|-----------|
| FROM | 10ms | 50ms | 500ms | 5000ms+ ❌ |
| SCROLL | 10ms | 10ms | 10ms | 10ms ✅ |
| SEARCH_AFTER | 10ms | 10ms | 10ms | 10ms ✅ |

## 注意事项

1. **SEARCH_AFTER模式**：需要传入 `pitId` 和 `searchAfter` 进行翻页
2. **SCROLL模式**：查询完成后需要清理scroll上下文
3. **PIT保持时间**：超过keepAlive时间后PIT会自动过期
4. **排序字段**：Search After需要确保排序字段组合唯一，通常添加 `_id` 作为 tiebreaker
