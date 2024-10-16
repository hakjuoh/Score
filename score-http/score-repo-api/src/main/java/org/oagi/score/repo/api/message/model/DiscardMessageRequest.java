package org.oagi.score.repo.api.message.model;

import org.oagi.score.repo.api.base.Request;
import org.oagi.score.repo.api.user.model.ScoreUser;

import java.math.BigInteger;

public class DiscardMessageRequest extends Request {

    private final BigInteger messageId;

    public DiscardMessageRequest(ScoreUser requester, BigInteger messageId) {
        super(requester);
        this.messageId = messageId;
    }

    public BigInteger getMessageId() {
        return messageId;
    }

}
