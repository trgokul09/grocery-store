package com.grocery.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public class AiDtos {

    public record ChatRequest(
        Long conversationId,
        @NotBlank @Size(max = 2000) String message,
        String currentPage
    ) {}

    public record TablePayload(
        String title,
        List<String> columns,
        List<List<String>> rows
    ) {}

    public record NavAction(
        String label,
        String path,
        String description
    ) {}

    public record DownloadAction(
        String label,
        String endpoint,
        String filename
    ) {}

    public record ChatResponse(
        Long conversationId,
        String conversationTitle,
        Long messageId,
        String answer,
        String intent,
        String providerUsed,
        List<String> toolsInvoked,
        List<TablePayload> tables,
        List<NavAction> navLinks,
        List<DownloadAction> downloads,
        List<String> followUpSuggestions,
        Instant timestamp
    ) {}

    public record MessageDto(
        Long id,
        String role,
        String content,
        String intent,
        List<String> toolsInvoked,
        List<TablePayload> tables,
        List<NavAction> navLinks,
        List<DownloadAction> downloads,
        List<String> followUpSuggestions,
        Instant createdAt
    ) {}

    public record ConversationSummaryDto(
        Long id,
        String title,
        String lastIntent,
        Instant createdAt,
        Instant updatedAt
    ) {}

    public record ConversationDetailDto(
        Long id,
        String title,
        String lastIntent,
        Instant createdAt,
        Instant updatedAt,
        List<MessageDto> messages
    ) {}

    public record PromptCategoryDto(
        String category,
        List<String> questions
    ) {}

    public record SuggestionsResponse(
        String welcomeMessage,
        String userRole,
        String activeProvider,
        List<String> quickSuggestions,
        List<PromptCategoryDto> categories
    ) {}
}
