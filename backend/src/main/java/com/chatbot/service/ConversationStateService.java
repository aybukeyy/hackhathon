package com.chatbot.service;

import com.chatbot.model.ConversationState;
import com.chatbot.model.FlowStep;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-session state store.
 * Replace with Redis or a database for multi-instance deployments.
 */
@Service
public class ConversationStateService {

    private final Map<String, ConversationState> store = new ConcurrentHashMap<>();

    public Optional<ConversationState> getState(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    public boolean hasActiveFlow(String sessionId) {
        ConversationState s = store.get(sessionId);
        return s != null && s.getStep() != null && s.getStep() != FlowStep.DONE;
    }

    public ConversationState createFlowState(String sessionId, String intent, String entity) {
        ConversationState state = new ConversationState();
        state.setSessionId(sessionId);
        state.setIntent(intent);
        state.setEntity(entity);
        state.setStep(FlowStep.ASK_SUBSCRIBER_NO);
        state.getData().put("subscriberNo", null);
        state.getData().put("amount", null);
        store.put(sessionId, state);
        return state;
    }

    public void save(ConversationState state) {
        store.put(state.getSessionId(), state);
    }

    public void clearState(String sessionId) {
        store.remove(sessionId);
    }

    public ConversationState getOrCreate(String sessionId) {
        return store.computeIfAbsent(sessionId, id -> {
            ConversationState s = new ConversationState();
            s.setSessionId(id);
            return s;
        });
    }
}
