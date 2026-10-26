package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        //change the input can not change hte payload
        input[0] = 99;
        byte[] out = record.payload();
        //change the output can not change the payload
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
        hugeLength[9] = 0x7f;                                                         // idLength becomes enormous
        assertThrows(IllegalArgumentException.class, () -> TaskRecord.fromBytes(hugeLength));
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
