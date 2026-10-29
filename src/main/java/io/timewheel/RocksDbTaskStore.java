package io.timewheel;

import org.rocksdb.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.rocksdb.RocksDBException;

/**
 * {@link TaskStore} backed by an embedded RocksDB. Two key spaces share one database:
 * {@code "a:" + id} for active tasks and
 * {@code "d:" + id} for dead letters; the value is
 * {@link TaskRecord#toBytes()}. Moving a record between them is a single {@link WriteBatch}, which
 * RocksDB applies atomically.
 *
 * <p>RocksDB appends every write to its own write-ahead log before acknowledging it. With
 * {@code syncWrites = true} (the default) each write is fsync'ed, so a record the store accepted
 * survives a process crash AND a power cut, at the cost of one disk flush per write. With
 * {@code false} it survives a process crash but the last few writes may be lost on a power cut;
 * choose it when throughput matters more than that window.
 *
 * <p>One process at a time: RocksDB locks the directory, a second open fails with
 * {@link TaskStoreException}.
 *
 * <p>Data written by step 4 used unprefixed keys and is not visible to this version.
 */

public class RocksDbTaskStore implements TaskStore{

    private static final System.Logger LOG = System.getLogger(RocksDbTaskStore.class.getName());
    private static final byte[] ACTIVE = "a:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DEAD = "d:".getBytes(StandardCharsets.US_ASCII);



    static {
        RocksDB.loadLibrary();

    }

    private final Object lock = new Object();
    private final Options options;
    private final WriteOptions writeOptions;
    private final RocksDB db;
    private boolean closed;

    public RocksDbTaskStore(Path directory) {
        this(directory, true);
    }

    public RocksDbTaskStore(Path directory, boolean syncWrites) {
        Options opts = new Options().setCreateIfMissing(true);
        WriteOptions writeOpts = new WriteOptions().setSync(syncWrites);
        RocksDB opened;

        try {
            Files.createDirectories(directory);
            opened = RocksDB.open(opts, directory.toString());
        }catch (IOException | RocksDBException e) {
            writeOpts.close();
            opts.close();
            throw new TaskStoreException("cannot open task store at " + directory, e);
        }
        this.options = opts;
        this.writeOptions = writeOpts;
        this.db = opened;
    }

    @Override
    public boolean putIfAbsent(TaskRecord record) {
        byte[] key = key(ACTIVE, record.id());
        synchronized (lock) {
            ensureOpen();
            try {
                if (db.get(key) != null){
                    return false;
                }
                db.put(writeOptions, key, record.toBytes());
                return true;
            }catch (RocksDBException e) {
                throw new TaskStoreException("cannot put record " + record, e);
            }
        }
    }

    @Override
    public boolean replace(TaskRecord record) {
        byte[] key = key(ACTIVE, record.id());
        synchronized (lock) {
            ensureOpen();
            try {
                if (db.get(key) == null) {
                    return false;
                }
                db.put(writeOptions, key, record.toBytes());
                return true;
            } catch (RocksDBException e) {
                throw new TaskStoreException("cannot update task " + record.id(), e);
            }
        }
    }

    @Override
    public boolean remove(String id) {
        byte[] key = key(ACTIVE, id);
        synchronized (lock) {
            ensureOpen();
            try {
                if (db.get(key) == null) {
                    return false;
                }
                db.delete(writeOptions, key);
                return true;
            }catch (RocksDBException e) {
                throw new TaskStoreException("cannot delete record " + id, e);
            }
        }
    }

    @Override
    public boolean contains(String id) {
        synchronized (lock) {
            ensureOpen();
            try {
                return db.get(key(ACTIVE, id)) != null;
            } catch (RocksDBException e) {
                throw new TaskStoreException("cannot look up task " + id, e);
            }
        }
    }

    @Override
    public List<TaskRecord> loadAll() {
        return loadSpace(ACTIVE);
    }

    @Override
    public boolean deadLetter(TaskRecord finalState) {
        byte[] activeKey = key(ACTIVE, finalState.id());
        synchronized (lock) {
            ensureOpen();
            try {
                if (db.get(activeKey) == null) {
                    return false;
                }
                try (WriteBatch batch = new WriteBatch()) {
                    batch.delete(activeKey);
                    batch.put(key(DEAD, finalState.id()), finalState.toBytes());
                    db.write(writeOptions,batch);
                }
                return true;
            }catch (RocksDBException e) {
                throw new TaskStoreException("cannot dead_letter task " + finalState.id(), e);
            }
        }
    }

    @Override
    public List<TaskRecord> loadDeadLetters() {
        return loadSpace(DEAD);
    }

    @Override
    public Optional<TaskRecord> requeueDeadLetter(String id, long deadlineEpochMs) {
        byte[] deadKey = key(DEAD, id);
        byte[] activeKey = key(ACTIVE, id);
        synchronized (lock) {
            ensureOpen();
            try {
                byte[] stored = db.get(deadKey);
                if (stored == null || db.get(activeKey) != null) {
                    return Optional.empty();
                }
                TaskRecord fresh = TaskRecord.fromBytes(stored).restarted(deadlineEpochMs);
                try (WriteBatch batch = new WriteBatch()) {
                    batch.delete(deadKey);
                    batch.put(activeKey, fresh.toBytes());
                    db.write(writeOptions,batch);

                }
                return Optional.of(fresh);

            }catch (RocksDBException e) {
                throw new TaskStoreException("cannot requeue dead_letter task " + id, e);
            }catch (IllegalArgumentException e) {
                throw new TaskStoreException("dead letter " + id + " is unreachable", e);
            }
        }
    }

    @Override
    public boolean removeDeadLetter(String id) {
        byte[] key = key(DEAD, id);
        synchronized (lock) {
            ensureOpen();
            try {
                if (db.get(key) == null) {
                    return false;
                }
                db.delete(writeOptions, key);
                return true;
            } catch (RocksDBException e) {
                throw new TaskStoreException("cannot discard dead letter " + id, e);
            }
        }
    }

    private List<TaskRecord> loadSpace(byte[] prefix) {
        synchronized (lock) {
            ensureOpen();
            List<TaskRecord> records = new ArrayList<>();
            try (RocksIterator it = db.newIterator()) {
                for (it.seekToFirst(); it.isValid(); it.next()) {
                    byte[] key = it.key();
                    if (!startsWith(key, prefix)) {
                        continue;
                    }
                    try {
                        records.add(TaskRecord.fromBytes(it.value()));
                    }catch (IllegalArgumentException e) {
                        LOG.log(System.Logger.Level.ERROR,
                                "skipping unreadable task record, key=" + new String(key, StandardCharsets.UTF_8), e);
                    }
                }
                it.status();
            }catch (RocksDBException e) {
                throw new TaskStoreException("cannot read stored tasks", e);
            }
            return records;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if(closed) {
                return;
            }
            closed = true;
            writeOptions.close();
            db.close();
            options.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("task store has been closed");
        }
    }

    private static byte[] key(byte[] prefix, String id) {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[prefix.length + idBytes.length];
        System.arraycopy(prefix, 0, key, 0, prefix.length);
        System.arraycopy(idBytes, 0, key, prefix.length, idBytes.length);
        return key;
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
