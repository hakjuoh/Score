package org.oagi.score.gateway.http.api.ai_management.memory;

import org.oagi.score.gateway.http.api.ai_management.repository.AiChatJsonSerializer;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Creates requester-scoped Spring AI chat-memory instances. */
@Component
public class ScoreChatMemoryFactory {

    private final RepositoryFactory repositoryFactory;
    private final TransactionOperations transactions;
    private final int maxMessages;

    @Autowired
    public ScoreChatMemoryFactory(RepositoryFactory repositoryFactory,
                                  ScoreAiProperties properties,
                                  PlatformTransactionManager transactionManager) {
        this(repositoryFactory, properties, new TransactionTemplate(transactionManager));
    }

    ScoreChatMemoryFactory(RepositoryFactory repositoryFactory,
                           ScoreAiProperties properties,
                           TransactionOperations transactions) {
        this.repositoryFactory = Objects.requireNonNull(repositoryFactory,
                "repositoryFactory must not be null");
        this.transactions = Objects.requireNonNull(transactions,
                "transactions must not be null");
        this.maxMessages = Math.max(4, Objects.requireNonNull(properties,
                "properties must not be null").getMemory().getMaxMessages());
    }

    public ChatMemory create(ScoreUser requester) {
        Objects.requireNonNull(requester, "requester must not be null");
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new ScoreChatMemoryRepository(
                        repositoryFactory.aiChatMemoryStorageRepository(
                                requester, AiChatJsonSerializer.getInstance()),
                        transactions))
                .maxMessages(maxMessages)
                .build();
    }
}
