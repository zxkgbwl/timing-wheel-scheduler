package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Extra rules for a store that claims durability: what was stored must still be there after the
 * store is closed and opened again on the same directory.
 */
abstract class DurableTaskStoreContractTest extends TaskStoreContractTest {

    private final List<Path> directories = new ArrayList<>();

    abstract TaskStore open(Path directory);

    Path newDirectory() {
        try {
            Path dir = Files.createTempDirectory("timewheel-store-");
            directories.add(dir);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    TaskStore create() {
        return open(newDirectory());
    }

    @AfterEach
    void deleteDirectories() {
        for (Path dir : directories) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // best effort: a leftover temp directory is not a test failure
            }
        }
        directories.clear();
    }

    private static Map<String, TaskRecord> byId(List<TaskRecord> records) {
        Map<String, TaskRecord> map = new HashMap<>();
        for (TaskRecord r : records) {
            map.put(r.id(), r);
        }
        return map;
    }

    @Test
    void recordsSurviveCloseAndReopen() {
        Path dir = newDirectory();
        byte[] binary = new byte[256];
        for (int i = 0; i < 256; i++) {
            binary[i] = (byte) i;
        }
        TaskStore first = open(dir);
        assertTrue(first.putIfAbsent(new TaskRecord("a", 111, binary)));
        assertTrue(first.putIfAbsent(new TaskRecord("订单-2", 222, new byte[0])));
        first.close();

        TaskStore second = open(dir);
        try {
            Map<String, TaskRecord> loaded = byId(second.loadAll());
            assertEquals(2, loaded.size());
            assertEquals(111, loaded.get("a").deadlineEpochMs());
            assertArrayEquals(binary, loaded.get("a").payload());
            assertEquals(222, loaded.get("订单-2").deadlineEpochMs());
        } finally {
            second.close();
        }
    }

    @Test
    void removalsSurviveReopenAndTheIdBecomesReusable() {
        Path dir = newDirectory();
        TaskStore first = open(dir);
        assertTrue(first.putIfAbsent(record("a", 1)));
        assertTrue(first.putIfAbsent(record("b", 2)));
        assertTrue(first.remove("a"));
        first.close();

        TaskStore second = open(dir);
        try {
            List<TaskRecord> loaded = second.loadAll();
            assertEquals(1, loaded.size());
            assertEquals("b", loaded.get(0).id());
            assertTrue(second.putIfAbsent(record("a", 3)));
        } finally {
            second.close();
        }
    }

    @Test
    void aReopenedStoreStillRejectsDuplicateIds() {
        Path dir = newDirectory();
        TaskStore first = open(dir);
        assertTrue(first.putIfAbsent(record("a", 1)));
        first.close();

        TaskStore second = open(dir);
        try {
            assertFalse(second.putIfAbsent(record("a", 2)));
            assertEquals(1, second.loadAll().get(0).deadlineEpochMs());
        } finally {
            second.close();
        }
    }

    @Test
    void retryStateAndDeadLettersSurviveReopen() {
        Path dir = newDirectory();
        TaskStore first = open(dir);
        assertTrue(first.putIfAbsent(record("retrying", 1)));
        assertTrue(first.putIfAbsent(record("dying", 2)));
        assertTrue(first.putIfAbsent(record("revived", 3)));
        assertTrue(first.replace(new TaskRecord("retrying", 9_000, new byte[] {1, 2, 3}, 2, "boom")));
        assertTrue(first.deadLetter(new TaskRecord("dying", 2, new byte[] {1, 2, 3}, 5, "fatal")));
        assertTrue(first.deadLetter(new TaskRecord("revived", 3, new byte[] {1, 2, 3}, 5, "fatal")));
        assertTrue(first.requeueDeadLetter("revived", 77).isPresent());
        first.close();

        TaskStore second = open(dir);
        try {
            Map<String, TaskRecord> active = byId(second.loadAll());
            assertEquals(2, active.size());
            assertEquals(2, active.get("retrying").attempts());
            assertEquals("boom", active.get("retrying").lastError());
            assertEquals(9_000, active.get("retrying").deadlineEpochMs());
            assertEquals(0, active.get("revived").attempts());
            assertEquals(77, active.get("revived").deadlineEpochMs());

            List<TaskRecord> dead = second.loadDeadLetters();
            assertEquals(1, dead.size());
            assertEquals("dying", dead.get(0).id());
            assertEquals(5, dead.get(0).attempts());
            assertEquals("fatal", dead.get(0).lastError());
        } finally {
            second.close();
        }
    }

    @Test
    void dataSurvivesManyReopenCycles() {
        Path dir = newDirectory();
        for (int cycle = 0; cycle < 5; cycle++) {
            TaskStore store = open(dir);
            try {
                assertEquals(cycle, store.loadAll().size());
                assertTrue(store.putIfAbsent(record("id-" + cycle, cycle)));
            } finally {
                store.close();
            }
        }
    }
}
