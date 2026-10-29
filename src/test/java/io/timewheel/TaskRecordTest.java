package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TaskRecordTest {

    private static byte[] allByteValues() {
        byte[] bytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    @Test
    void roundTripsThroughBytes() {
        TaskRecord original = new TaskRecord("order-42", 1_700_000_000_123L, allByteValues());
        TaskRecord copy = TaskRecord.fromBytes(original.toBytes());
        assertEquals(original, copy);
        assertEquals("order-42", copy.id());
        assertEquals(1_700_000_000_123L, copy.deadlineEpochMs());
        assertArrayEquals(allByteValues(), copy.payload());
    }

    @Test
    void emptyAndNullPayloadsBothBecomeEmpty() {
        assertEquals(0, new TaskRecord("a", 1, null).payload().length);
        assertEquals(0, TaskRecord.fromBytes(new TaskRecord("a", 1, new byte[0]).toBytes()).payload().length);
    }

    @Test
    void unicodeIdsSurvive() {
        TaskRecord record = new TaskRecord("订单-✓-42", 5, new byte[] {1});
        assertEquals("订单-✓-42", TaskRecord.fromBytes(record.toBytes()).id());
    }

    @Test
    void payloadIsCopiedOnTheWayInAndOut() {
        byte[] input = {1, 2, 3};
        TaskRecord record = new TaskRecord("a", 1, input);
        input[0] = 99;
        byte[] out = record.payload();
        out[1] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, record.payload());
    }

    @Test
    void invalidIdsAreRejected() {
        assertThrows(NullPointerException.class, () -> new TaskRecord(null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new TaskRecord("", 1, null));
    }

    @Test
    void corruptBytesAreRejectedNotMisread() {
        byte[] good = new TaskRecord("abc", 7, new byte[] {1, 2, 3, 4}).toBytes();

        assertThrows(IllegalArgumentException.class, () -> TaskRecord.fromBytes(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> TaskRecord.fromBytes(Arrays.copyOf(good, good.length - 1)));   // truncated
        assertThrows(IllegalArgumentException.class,
                () -> TaskRecord.fromBytes(Arrays.copyOf(good, good.length + 1)));   // trailing garbage

        byte[] wrongVersion = good.clone();
        wrongVersion[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> TaskRecord.fromBytes(wrongVersion));

        byte[] hugeLength = good.clone();
        hugeLength[13] = 0x7f;                                                        // idLength becomes enormous
        assertThrows(IllegalArgumentException.class, () -> TaskRecord.fromBytes(hugeLength));
    }

    @Test
    void attemptsAndLastErrorSurviveTheRoundTrip() {
        TaskRecord original = new TaskRecord("a", 10, new byte[] {1}, 3, "IllegalStateException: 订单不存在");
        TaskRecord copy = TaskRecord.fromBytes(original.toBytes());
        assertEquals(original, copy);
        assertEquals(3, copy.attempts());
        assertEquals("IllegalStateException: 订单不存在", copy.lastError());
        assertEquals(null, TaskRecord.fromBytes(new TaskRecord("b", 1, null).toBytes()).lastError());
    }

    @Test
    void recordsWrittenByStepFourStillLoad() {
        byte[] id = "old".getBytes(StandardCharsets.UTF_8);
        byte[] payload = {5, 6};
        ByteBuffer v1 = ByteBuffer.allocate(1 + 8 + 4 + id.length + 4 + payload.length);
        v1.put((byte) 1).putLong(777).putInt(id.length).put(id).putInt(payload.length).put(payload);

        TaskRecord loaded = TaskRecord.fromBytes(v1.array());
        assertEquals("old", loaded.id());
        assertEquals(777, loaded.deadlineEpochMs());
        assertEquals(0, loaded.attempts());
        assertEquals(null, loaded.lastError());
        assertArrayEquals(payload, loaded.payload());
    }

    @Test
    void afterFailedAttemptCountsTheFailureAndMovesTheDeadline() {
        TaskRecord first = new TaskRecord("a", 100, new byte[] {1});
        TaskRecord second = first.afterFailedAttempt("boom", 5_000);
        assertEquals(1, second.attempts());
        assertEquals("boom", second.lastError());
        assertEquals(5_000, second.deadlineEpochMs());
        assertArrayEquals(new byte[] {1}, second.payload());
        assertEquals(2, second.afterFailedAttempt("again", 9_000).attempts());
        assertEquals(0, first.attempts());                       // the original is unchanged

        TaskRecord fresh = second.restarted(42);
        assertEquals(0, fresh.attempts());
        assertEquals(null, fresh.lastError());
        assertEquals(42, fresh.deadlineEpochMs());
    }

    @Test
    void aVeryLongErrorIsTruncatedAndNegativeAttemptsAreRejected() {
        String huge = "x".repeat(10_000);
        assertEquals(500, new TaskRecord("a", 1, null, 1, huge).lastError().length());
        assertThrows(IllegalArgumentException.class, () -> new TaskRecord("a", 1, null, -1, null));
    }

    @Test
    void equalityCoversAllFieldsAndToStringHidesThePayload() {
        TaskRecord a = new TaskRecord("a", 1, new byte[] {1});
        assertEquals(a, new TaskRecord("a", 1, new byte[] {1}));
        assertTrue(!a.equals(new TaskRecord("b", 1, new byte[] {1})));
        assertTrue(!a.equals(new TaskRecord("a", 2, new byte[] {1})));
        assertTrue(!a.equals(new TaskRecord("a", 1, new byte[] {2})));
        assertTrue(!a.toString().contains("[B"));
    }
}
