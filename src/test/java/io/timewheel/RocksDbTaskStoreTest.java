package io.timewheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

class RocksDbTaskStoreTest extends DurableTaskStoreContractTest {

    @Override
    TaskStore open(Path directory) {
        return new RocksDbTaskStore(directory);
    }

    @Test
    void theDirectoryIsLockedAgainstASecondOpen() {
        Path dir = newDirectory();
        TaskStore first = open(dir);
        try {
            assertThrows(TaskStoreException.class, () -> open(dir));
        } finally {
            first.close();
        }
        open(dir).close(); // after closing, opening works again
    }

    @Test
    void relaxedSyncModeStillStoresAndReloads() {
        Path dir = newDirectory();
        RocksDbTaskStore first = new RocksDbTaskStore(dir, false);
        assertTrue(first.putIfAbsent(record("a", 1)));
        first.close();
        TaskStore second = open(dir);
        try {
            assertEquals(1, second.loadAll().size());
        } finally {
            second.close();
        }
    }

    @Test
    void anUnreadableEntryIsSkippedInsteadOfBlockingRecovery() throws Exception {
        Path dir = newDirectory();
        TaskStore first = open(dir);
        assertTrue(first.putIfAbsent(record("good", 1)));
        first.close();

        RocksDB.loadLibrary();
        try (Options options = new Options().setCreateIfMissing(true);
             RocksDB raw = RocksDB.open(options, dir.toString())) {
            raw.put("a:bad".getBytes(), new byte[] {9, 9, 9});
        }

        TaskStore second = open(dir);
        try {
            assertEquals(1, second.loadAll().size());
            assertEquals("good", second.loadAll().get(0).id());
        } finally {
            second.close();
        }
    }
}
