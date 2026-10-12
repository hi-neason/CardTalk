package com.cardtalk.cloud;

public final class CloudConflict extends RuntimeException {
    public final CloudDtos.Operation operation;
    public final CloudDtos.Entity remote;
    public CloudConflict(CloudDtos.Operation operation,CloudDtos.Entity remote) { super("云端已有更新，请明确选择保留哪一版");this.operation=operation;this.remote=remote; }
}
