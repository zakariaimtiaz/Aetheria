package com.dis.fshipbot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Objects;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {
    private String question;
    private String answer;
    private long processingTimeMs;
    private String model;
    private List<SourceInfo> sources;
    private String error;
    private boolean hasSources;
    private String sessionId;
    private Integer conversationSize;
    private Double confidence;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SourceInfo {
        private String documentName;
        private String documentType;
        private int chunkIndex;
        private int totalChunks;
        private double similarity;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SourceInfo that = (SourceInfo) o;
            return Objects.equals(documentName, that.documentName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(documentName);
        }
    }
}