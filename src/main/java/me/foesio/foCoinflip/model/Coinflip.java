package me.foesio.foCoinflip.model;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class Coinflip {

    public enum Status {
        OPEN,
        MATCHING,
        RESOLVED,
        CANCELLED
    }

    private final UUID id;
    private final UUID creatorId;
    private final String creatorName;
    private final double stake;
    private final long createdAt;
    private final AtomicReference<Status> status;

    public Coinflip(UUID id, UUID creatorId, String creatorName, double stake, long createdAt) {
        this.id = id;
        this.creatorId = creatorId;
        this.creatorName = creatorName;
        this.stake = stake;
        this.createdAt = createdAt;
        this.status = new AtomicReference<>(Status.OPEN);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public String getCreatorName() {
        return creatorName;
    }

    public double getStake() {
        return stake;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public Status getStatus() {
        return status.get();
    }

    public boolean claimForMatch() {
        return status.compareAndSet(Status.OPEN, Status.MATCHING);
    }

    public void markResolved() {
        status.set(Status.RESOLVED);
    }

    public boolean reopen() {
        return status.compareAndSet(Status.MATCHING, Status.OPEN);
    }

    public boolean cancel() {
        return status.compareAndSet(Status.OPEN, Status.CANCELLED);
    }
}
