package com.example.es.service;

/**
 * 嵌入向量服务接口
 * 用户需要根据自己的嵌入模型实现此接口
 * 例如：OpenAI Embeddings API, Ollama, 本地模型等
 */
public interface EmbeddingService {

    /**
     * 将文本转换为嵌入向量
     *
     * @param text 输入文本
     * @return 嵌入向量
     */
    float[] embed(String text);
}
