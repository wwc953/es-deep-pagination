# ES 深度分页与混合搜索

基于 Spring Boot 3 和 Elasticsearch 8.x 的搜索解决方案，涵盖深度分页与语义混合搜索。

## 特性

### 三种分页模式

| 模式 | 适用场景 | 优点 | 缺点 |
|------|---------|------|------|
| **FROM** | 浅分页（from < 10000） | 简单直接 | 性能随 from 增大而下降 |
| **SCROLL** | 全量导出 | 高效遍历全部数据 | 实时性差，快照数据 |
| **SEARCH_AFTER + PIT** | 深度分页（推荐） | 实时性好，性能稳定 | 需要客户端维护状态 |

### 混合搜索（BM25 + KNN + RRF）

在关键词搜索基础上新增向量语义搜索能力：

| 能力 | 说明 |
|------|------|
| **BM25 关键词搜索** | multiMatch 匹配 title^2 + content |
| **KNN 向量语义搜索** | 基于 dense_vector 字段的近似最近邻搜索 |
| **RRF 融合排序** | Reciprocal Rank Fusion 算法融合两种搜索结果 |
| **可插拔 Embedding** | 自定义 `EmbeddingService` 实现，支持 OpenAI / Ollama / 本地模型 |
| **深度分页** | Search After + PIT 支持无限翻页 |

## 技术栈

- Spring Boot 3.3.1
- Elasticsearch 8.18.1（Java Client）
- Java 17
- Maven

## 快速开始

### 1. 启动 Elasticsearch

```bash
docker run -d --name es \
  -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  docker.elastic.co/elasticsearch/elasticsearch:8.18.1
```

### 2. 启动应用

```bash
mvn spring-boot:run
```

应用启动后会自动创建 `documents` 索引并插入 100,000 条测试数据（含随机向量）。

### 3. 验证索引

```bash
curl http://localhost:9200/documents/_mapping?pretty
```

## API 接口

### 分页查询接口

---

#### 1. 深度分页查询（通用）

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

#### 2. 第一页查询（简化版）

**GET** `/api/v1/pagination/search-first?index=documents&keyword=测试&pageSize=20`

#### 3. 下一页查询（Search After 模式）

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

#### 4. 继续 Scroll 滚动

**POST** `/api/v1/pagination/scroll-next`

```json
{
  "scrollId": "DXF1ZXJ5QW5...",
  "index": "documents",
  "pageSize": 20,
  "mode": "SCROLL"
}
```

#### 5. 清理上下文

```bash
# 清理 Scroll
curl -X DELETE http://localhost:18080/api/v1/pagination/scroll/{scrollId}

# 清理 PIT
curl -X DELETE http://localhost:18080/api/v1/pagination/pit/{pitId}
```

---

### 混合搜索接口

---

#### 6. 混合搜索（BM25 + KNN + RRF）

**POST** `/api/v1/pagination/hybrid-search`

结合关键词搜索与向量语义搜索，使用 RRF（Reciprocal Rank Fusion）融合排序。

**方式一：传入预计算向量**

```json
{
  "index": "documents",
  "keyword": "Spring Boot",
  "queryVector": [0.0123, -0.0456, 0.0789, "..."],
  "category": "tech",
  "status": 1,
  "pageSize": 20
}
```

**方式二：服务端自动计算向量（需配置 EmbeddingService）**

```json
{
  "index": "documents",
  "keyword": "Spring Boot",
  "category": "tech",
  "pageSize": 20
}
```

> 当未传入 `queryVector` 时，服务端会调用 `EmbeddingService.embed(keyword)` 自动生成向量。

**翻页查询（Search After + PIT）**

```json
{
  "index": "documents",
  "keyword": "Spring Boot",
  "queryVector": [0.0123, -0.0456, "..."],
  "pageSize": 20,
  "pitId": "从上一页响应中获取",
  "searchAfter": ["从上一页响应中获取"]
}
```

---

### 索引管理接口

---

#### 7. 创建索引

**POST** `/api/v1/index/create/{indexName}`

创建包含向量字段映射的索引。

#### 8. 删除索引

**DELETE** `/api/v1/index/{indexName}`

#### 9. 检查索引是否存在

**GET** `/api/v1/index/exists/{indexName}`

---

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
  "total": 100000,
  "pageSize": 20,
  "hasNext": true,
  "pitId": "46ToAwMDaWR...",
  "searchAfter": [1704067200000, "document-20"],
  "costTime": 15
}
```

> 注意：响应中不包含 `titleVector` 字段（已通过 `@JsonIgnore` 和 `_source` 过滤双重排除）。

## 数据模型

| 字段 | ES 类型 | 说明 |
|------|---------|------|
| `id` | String | 文档 ID（ES 自动生成） |
| `title` | text | 标题（标准分词器） |
| `content` | text | 内容（标准分词器） |
| `category` | keyword | 分类过滤字段 |
| `author` | keyword | 作者 |
| `status` | integer | 状态过滤字段 |
| `createTime` | date | 创建时间 |
| `updateTime` | date | 更新时间 |
| `titleVector` | dense_vector (768维, cosine) | 标题向量（用于语义搜索） |

## 配置说明

`application.yml`：

```yaml
deep-pagination:
  # 分页配置
  max-page-size: 100              # FROM 模式最大分页大小
  scroll-keep-alive-minutes: 5    # Scroll 上下文保持时间
  pit-keep-alive-minutes: 2       # PIT 保持时间

  # 向量搜索配置
  vector-dimensions: 768          # 向量维度（需与 EmbeddingService 输出维度一致）
  knn-k: 10                       # KNN 返回 top-k 个最近邻
  knn-num-candidates: 100         # KNN 候选数（越大越精确，性能越低）
  rrf-window-size: 50             # RRF 窗口大小
  rrf-rank-constant: 60           # RRF 排名常数
```

### RRF 参数调优

| 参数 | 说明 | 建议 |
|------|------|------|
| `knn-k` | 向量搜索返回的结果数 | 10-50，越大召回率越高 |
| `knn-num-candidates` | KNN 候选池大小 | 100-1000，越大精度越高 |
| `rrf-window-size` | 参与融合的结果数 | 默认 50，覆盖两种搜索的 top 结果 |
| `rrf-rank-constant` | 控制排名权重衰减 | 默认 60，越大高排名影响越小 |

## 自定义 EmbeddingService

实现 `EmbeddingService` 接口并注册为 Spring Bean：

```java
@Service
public class OllamaEmbeddingService implements EmbeddingService {

    @Override
    public float[] embed(String text) {
        // 调用 Ollama / OpenAI / 本地模型生成向量
        // 返回维度需与 vector-dimensions 配置一致
        return new float[]{0.01f, -0.02f, 0.03f, /* ... 768维 */};
    }
}
```

接口定义：

```java
public interface EmbeddingService {
    float[] embed(String text);
}
```

## 前端调用示例

### 深度分页（无限滚动）

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
  result.hasNext = nextResult.hasNext;
  result.pitId = nextResult.pitId;
  result.searchAfter = nextResult.searchAfter;
}
```

### 混合搜索

```javascript
// 混合搜索（传入预计算向量）
const response = await fetch('/api/v1/pagination/hybrid-search', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    index: 'documents',
    keyword: 'Spring Boot',
    queryVector: [0.01, -0.02, 0.03], // 768维向量
    category: 'tech',
    pageSize: 20
  })
});

const result = await response.json();

// 翻页
const nextResponse = await fetch('/api/v1/pagination/hybrid-search', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    index: 'documents',
    keyword: 'Spring Boot',
    queryVector: [0.01, -0.02, 0.03],
    pageSize: 20,
    pitId: result.pitId,
    searchAfter: result.searchAfter
  })
});
```

### Scroll 模式全量导出

```javascript
async function exportAll() {
  let allData = [];
  let scrollId = null;
  let hasMore = true;

  // 首次查询
  const response = await fetch('/api/v1/pagination/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ index: 'documents', pageSize: 1000, mode: 'SCROLL' })
  });

  let result = await response.json();
  allData.push(...result.data);
  scrollId = result.scrollId;

  // 继续滚动
  while (hasMore && result.data.length > 0) {
    const nextResponse = await fetch('/api/v1/pagination/scroll-next', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ index: 'documents', scrollId, pageSize: 1000, mode: 'SCROLL' })
    });
    result = await nextResponse.json();
    allData.push(...result.data);
    hasMore = result.hasNext;
  }

  // 清理 Scroll 上下文
  await fetch(`/api/v1/pagination/scroll/${scrollId}`, { method: 'DELETE' });
  return allData;
}
```

## 性能对比

| 分页方式 | 第1页 | 第100页 | 第1000页 | 第10000页 |
|---------|-------|---------|----------|-----------|
| FROM | 10ms | 50ms | 500ms | 5000ms+ |
| SCROLL | 10ms | 10ms | 10ms | 10ms |
| SEARCH_AFTER | 10ms | 10ms | 10ms | 10ms |
| HYBRID_SEARCH | 20ms | 20ms | 20ms | 20ms |

> 混合搜索因同时执行 KNN + BM25 + RRF 融合，耗时略高于纯关键词搜索，但仍保持在毫秒级。

## 注意事项

1. **SEARCH_AFTER 模式**：需要传入 `pitId` 和 `searchAfter` 进行翻页
2. **SCROLL 模式**：查询完成后需要清理 scroll 上下文
3. **PIT 保持时间**：超过 keepAlive 时间后 PIT 会自动过期
4. **排序字段**：Search After 需要确保排序字段组合唯一，通常添加 `_doc` 作为 tiebreaker
5. **向量维度**：`vector-dimensions` 配置需与 `EmbeddingService` 实际输出维度一致
6. **混合搜索向量**：不传 `queryVector` 时需配置 `EmbeddingService` Bean，否则会返回 400 错误
