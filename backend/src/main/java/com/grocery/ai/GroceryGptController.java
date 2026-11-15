package com.grocery.ai;

import com.grocery.ai.AiDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class GroceryGptController {

    private final GroceryGptService gptService;

    public GroceryGptController(GroceryGptService gptService) {
        this.gptService = gptService;
    }

    @GetMapping("/suggestions")
    public SuggestionsResponse getSuggestions() {
        return gptService.getSuggestions();
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req) {
        return gptService.chat(req);
    }

    @GetMapping("/conversations")
    public List<ConversationSummaryDto> listConversations() {
        return gptService.listConversations();
    }

    @GetMapping("/conversations/{id}")
    public ConversationDetailDto getConversation(@PathVariable Long id) {
        return gptService.getConversation(id);
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Map<String, String>> deleteConversation(@PathVariable Long id) {
        gptService.deleteConversation(id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @DeleteMapping("/conversations")
    public ResponseEntity<Map<String, String>> clearAllConversations() {
        gptService.clearAllConversations();
        return ResponseEntity.ok(Map.of("status", "cleared"));
    }
}
