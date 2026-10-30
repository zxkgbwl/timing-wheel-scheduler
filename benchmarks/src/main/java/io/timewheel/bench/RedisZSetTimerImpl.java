package io.timewheel.bench;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import redis.clients.jedis.JedisPooled;

/**
 * The classic "delay queue on Redis": {@code ZADD key deadline id} to schedule, {@code ZREM} to
 * cancel, and one poller that runs {@code ZRANGEBYSCORE key 0 now LIMIT 0 1000} and claims each
 * due id with {@code ZREM} (only the caller that removes it runs it).
 *
 * <p>Redis can only store data, so the Runnable stays in a local map keyed by id; that map is
 * client memory and is part of what this baseline costs. Every schedule and cancel is a network
 * round trip, which is exactly the property being measured: it is not pipelined, because a
 * caller wanting a handle back cannot batch. Start Redis first, e.g.
 * {@code docker run --rm -p 6379:6379 redis:7}.
 */
final class RedisZSetTimerImpl implements TimerImpl {

    private static final Pattern USED_MEMORY = Pattern.compile("used_memory:(\\d+)");

    private final JedisPooled redis;
    private final String key = "timewheel-bench:" + System.nanoTime();
    private final ConcurrentHashMap<String, Runnable> tasks = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Thread poller;
    private volatile boolean running = true;

    RedisZSetTimerImpl(String host, int port) {
        this.redis = new JedisPooled(host, port);
        this.poller = new Thread(this::poll, "bench-redis-poller");
        this.poller.setDaemon(true);
        this.poller.start();
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public Object schedule(long delayMs, Runnable task) {
        String id = Long.toString(ids.incrementAndGet());
        tasks.put(id, task);
        redis.zadd(key, System.currentTimeMillis() + delayMs, id);
        return id;
    }

    @Override
    public void cancel(Object handle) {
        String id = (String) handle;
        if (redis.zrem(key, id) > 0) {
            tasks.remove(id);
        }
    }

    @Override
    public long serverMemoryBytes() {
        // JedisPooled 不支持 info() 等集群管理命令，改用普通 Jedis 实例获取服务器内存
        try (redis.clients.jedis.Jedis tempJedis = new redis.clients.jedis.Jedis("localhost", 6379)) {
            Matcher m = USED_MEMORY.matcher(tempJedis.info("memory"));
            return m.find() ? Long.parseLong(m.group(1)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void poll() {
        while (running) {
            try {
                List<String> due = redis.zrangeByScore(key, 0, System.currentTimeMillis(), 0, 1000);
                for (String id : due) {
                    if (redis.zrem(key, id) == 1) {
                        Runnable task = tasks.remove(id);
                        if (task != null) {
                            task.run();
                        }
                    }
                }
                if (due.isEmpty()) {
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                running = running && !Thread.currentThread().isInterrupted();
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        poller.interrupt();
        poller.join(2_000);
        redis.del(key);
        redis.close();
    }
}
