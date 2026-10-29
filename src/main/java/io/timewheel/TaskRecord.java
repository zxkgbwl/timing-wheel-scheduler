package io.timewheel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * record to store the task, Runnable can not be persisted, wheel runs a monotonic clock,
 * it become meaningless after the restart, so wall-clock time should be persisted
 * payload is immutable
 */
public final class TaskRecord {
    private static final byte VERSION_1 = 1;
    private static final byte VERSION_2 = 2;
    //version 1 byte, deadline 8 bytes, idLength 4 bytes
    private static final int MAX_ERROR_CHARS = 500;

    private final String id;
    private final long deadlineEpochMs;
    private final byte[] payload;
    private final int attempts;
    private final String lastError;

    public TaskRecord(String id, long deadlineEpochMs, byte[] payload) {
        this(id, deadlineEpochMs, payload, 0, null);
    }

    public TaskRecord(String id, long deadlineEpochMs, byte[] payload, int attempts, String lastError) {
        Objects.requireNonNull(id, "id is null");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id is empty");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0");
        }
        this.id = id;
        this.deadlineEpochMs = deadlineEpochMs;
        this.payload = payload == null ? new byte[0] : payload.clone();

        this.attempts = attempts;
        this.lastError = lastError == null || lastError.length() <= MAX_ERROR_CHARS ? lastError
                : lastError.substring(0, MAX_ERROR_CHARS);
    }

    public String id() {
        return id;
    }

    public long deadlineEpochMs() {
        return deadlineEpochMs;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public int attempts() {
        return attempts;
    }

    public String lastError() {
        return lastError;
    }

    public TaskRecord afterFailedAttempt(String error, long nextDeadlineEpochMs) {
        return new TaskRecord(id, nextDeadlineEpochMs, payload, attempts + 1, error);
    }

    public TaskRecord restarted(long newDeadlineEpochMs) {
        return new TaskRecord(id, newDeadlineEpochMs, payload, 0, null);
    }

    //layout : version(1) | deadline(8) | idLength(4) | id | payloadLength(4) \ payload
    //TaskRecord to Byte
    public byte[] toBytes() {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] errBytes = lastError == null ? null : lastError.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 8 + 4 + 4 + idBytes.length + 4 + payload.length
                + 4 + (errBytes == null ? 0 : errBytes.length));
        buffer.put(VERSION_2);
        buffer.putLong(deadlineEpochMs);
        buffer.putInt(attempts);
        buffer.putInt(idBytes.length);
        buffer.put(idBytes);
        buffer.putInt(payload.length);
        buffer.put(payload);
        if (errBytes == null) {
            buffer.putInt(-1);
        } else {
            buffer.putInt(errBytes.length);
            buffer.put(errBytes);
        }
        return buffer.array();
    }

    //byte to TaskRecord
    public static TaskRecord fromBytes(byte[] bytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte version = buffer.get();
            if (version != VERSION_1 && version != VERSION_2) {
                throw new IllegalArgumentException("unsupported record version " + version);
            }
            long deadline = buffer.getLong();
            int attempts = version == VERSION_2 ? buffer.getInt() : 0;
            byte[] idBytes = readBlock(buffer, false);
            byte[] payload = readBlock(buffer, false);
            String error = null;
            if (version == VERSION_2) {
                byte[] errBytes = readBlock(buffer, true);
                error = errBytes == null ? null : new String(errBytes, StandardCharsets.UTF_8);
            }
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("trailing bytes after record");
            }
            return new TaskRecord(new String(idBytes, StandardCharsets.UTF_8), deadline, payload, attempts, error);
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated record", e);
        }
    }

    private static byte[] readBlock(ByteBuffer buffer, boolean nullable) {
        int length = buffer.getInt();
        if (nullable && length == -1) {
            return null;
        }
        if (length < 0 || length > buffer.remaining()) {
            throw new IllegalArgumentException("invalid length " + length);
        }
        byte[] block = new byte[length];
        buffer.get(block);
        return block;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TaskRecord)) {
            return false;
        }
        TaskRecord other = (TaskRecord) o;
        return deadlineEpochMs == other.deadlineEpochMs && attempts == other.attempts
                && id.equals(other.id) && Arrays.equals(payload, other.payload)
                && Objects.equals(lastError, other.lastError);
    }

    @Override
    public int hashCode() {
        return (Objects.hash(id, deadlineEpochMs, attempts, lastError) * 31) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "TaskRecord{id=" + id + ", deadlineEpochMs=" + deadlineEpochMs + ", attempts=" + attempts
                + ", payloadBytes=" + payload.length + (lastError == null ? "" : ", lastError=" + lastError) + '}';
    }



}
