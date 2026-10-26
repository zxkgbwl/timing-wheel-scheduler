package io.timewheel;

import org.rocksdb.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RocksDbTaskStore implements TaskStore{

    private static final System.Logger LOG = System.getLogger(RocksDbTaskStore.class.getName());

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
        byte[] key = key(record.id());
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
    public boolean remove(String id) {
        byte[] key = key(id);
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
    public List<TaskRecord> loadAll() {
        synchronized (lock) {
            ensureOpen();
            List<TaskRecord> records = new ArrayList<>();
            try (RocksIterator iterator = db.newIterator()) {
                for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                    try {
                        records.add(TaskRecord.fromBytes(iterator.value()));
                    }catch (IllegalArgumentException e) {
                        LOG.log(System.Logger.Level.ERROR, "skipping unreadable task record key ="
                                + new String(iterator.key(), StandardCharsets.UTF_8), e);
                    }

                }
                iterator.status();

            }catch (RocksDBException e){
                throw  new TaskStoreException("cannot read sotred tasks",e);
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

    private static byte[] key(String id) {
        return id.getBytes(StandardCharsets.UTF_8);
    }



}
