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
    private static final byte FORMAT_VERSION = 1;
    //version 1 byte, deadline 8 bytes, idLength 4 bytes
    private static final int HEADER_BYTES = 1 + 8 + 4;

    private final String id;
    private final long deadlineEpochMs;
    private final byte[] payload;

    public TaskRecord(String id, long deadlineEpochMs, byte[] payload) {
        Objects.requireNonNull(id, "id is null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id is blank");
        }
        this.id = id;
        this.deadlineEpochMs = deadlineEpochMs;
        this.payload = payload == null ? new byte[0] : payload.clone();
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

    //layout : version(1) | deadline(8) | idLength(4) | id | payloadLength(4) \ payload
    //TaskRecord to Byte
    public byte[] toBytes() {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate (HEADER_BYTES + idBytes.length + 4 + payload.length);
        buffer.put(FORMAT_VERSION); //1
        buffer.putLong(deadlineEpochMs); //8
        buffer.putInt(idBytes.length); //4
        buffer.put(idBytes); //idBytes.length varied
        buffer.putInt(payload.length); //4
        buffer.put(payload); //payload.length
        return buffer.array();
    }

    //byte to TaskRecord
    public static TaskRecord fromBytes(byte[] bytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte version = buffer.get();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("invalid format version");
            }
            long deadlineEpochMs = buffer.getLong();
            byte[] idBytes = readBlock(buffer);
            byte[] payload = readBlock(buffer);
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("tailing type after record");
            }
            return new TaskRecord(new String(idBytes, StandardCharsets.UTF_8), deadlineEpochMs, payload);

        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated record", e);
        }
    }

    private static byte[] readBlock (ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            throw new IllegalArgumentException("invalid length" + length);
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
        if(!(o instanceof TaskRecord)) {
            return false;
        }
        TaskRecord other = (TaskRecord) o;
        return deadlineEpochMs == other.deadlineEpochMs
                && id.equals(other.id)
                && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deadlineEpochMs, id) * 31 + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "TaskRecord{id=" + id + ", deadlineEpochMs=" + deadlineEpochMs
                + ", payloadBytes=" + payload.length + "}";
    }




}
