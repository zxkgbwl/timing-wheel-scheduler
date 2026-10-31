# 时间轮任务调度器phase1技术总结

重点是数据结构和调用关系

## 图 1：TimerTask 状态机

```mermaid
stateDiagram-v2
    direction LR
    [*] --> PENDING: new TimerTask(delayMs, action)

    PENDING --> CANCELLED: cancel() CAS 成功
    PENDING --> FIRED: runIfInPending() / tryFire() CAS 成功

    state FIRED {
        [*] --> 执行action
        执行action --> 正常结束
        执行action --> 异常结束: 抛出 Throwable，记录 ERROR 日志
    }

    note right of PENDING
        对非 PENDING 状态调用 cancel() 或 tryFire()，
        CAS 失败并返回 false，状态不变
    end note

    note right of CANCELLED
        若 entry 不为 null，
        调用 entry.remove() 从时间轮槽位中摘除
    end note

    CANCELLED --> [*]
    FIRED --> [*]
```
1. Task是最终业务的逻辑单位，比如在订单履行中30分钟定时任务检查用户是否付款，如果用户不付款，取消订单
2. Task的初始状态是pending， 通过CAS修改状态，取消后变为Cancelled， 执行后变为Fired， 不管执行的结果如何task会变为fired
3. Entry是双链表的节点，用于放task，task 和 entry是一一对应的关系，相互引用，task执行或者取消，entry包含task会从双链表中删除

## 图 2：TimerTaskEntry 类图（与 TimerTask、TimerTaskList 的关系）

```mermaid
classDiagram
    direction LR
    class TimerTask {
        -volatile TimerTaskEntry entry
        +cancel() boolean
        ~setEntry(TimerTaskEntry)
    }
    class TimerTaskEntry {
        ~volatile TimerTaskList list
        ~TimerTaskEntry next
        ~TimerTaskEntry prev
        ~final TimerTask task
        ~final long expirationMs
        ~cancelled() boolean
        ~remove() void
    }
    class TimerTaskList {
        ~remove(TimerTaskEntry)
    }
    TimerTask "1" <--> "1" TimerTaskEntry : 构造时互相引用
    TimerTaskList "1" -- "*" TimerTaskEntry : list 指向所属槽位
    TimerTaskEntry --> TimerTaskEntry : next / prev
    note for TimerTaskEntry "task == null 时为哨兵节点\nnext/prev 由所属 list 的锁保护"
```
1. task 和entry是一一对应的，entry的作用就是维护了双链表的结构，entry是TimerTaskList 的一个节点，TimerTaskList是时间轮中的一个桶，也就是说一个桶是一个list，entry是list中的一个节点

## 图 3：TimerTaskList 槽位结构（环形双向链表与 DelayQueue）

```mermaid
flowchart LR
    subgraph DQ["DelayQueue（按槽位 expirationMs 排序）"]
        direction TB
        B1["TimerTaskList<br/>exp=1000"]
        B2["TimerTaskList<br/>exp=1020"]
    end
    subgraph L["单个 TimerTaskList：带哨兵的环形双向链表"]
        R(("root<br/>哨兵")) <--> E1["Entry<br/>task A"] <--> E2["Entry<br/>task B"] <--> R
    end
    B1 -.->|展开| L
    C["taskCounter<br/>（所有槽位共享）"] -.-> L
```
1.每个TimerTaskList是一个槽位，每个槽位只有一个任务到期时间，所有的任务通过双向链表连接起来，所有任务都是一个到期时间
2.DelayQueue只保存有任务的桶（TaskTimerList），而且是按照到期时间顺序排列，快要到期排在前面，所以理论上我们可以通过两个路径找到一个任务，一个是delayqueue，一个是通过wheel，只是delayqueue是O（1）的

## 图 4：TimingWheel 层级数据结构

```mermaid
flowchart TB
    subgraph L0["第 0 层 TimingWheel（roundUp=true）<br/>tickMs=1ms · 实际覆盖 9ms"]
        direction LR
        P0(["currentTick"]) -. "当前 tick 的槽位不放任务" .-> S00["[0]"]
        S00 --- S01["[1]"] --- S02["[2]"] --- S0d["…"] --- S09["[9]"]
        S09 -. 环形复用 .-> S00
    end

    subgraph L1["第 1 层 TimingWheel（roundUp=false）<br/>tickMs=9ms · 覆盖 90ms"]
        direction LR
        P1(["currentTick"]) -.-> S10["[0]"]
        S10 --- S11["[1]"] --- S1d["…"] --- S19["[9]"]
    end

    subgraph L2["第 2 层 TimingWheel（首次溢出时才创建）<br/>tickMs=90ms · 覆盖 900ms"]
        direction LR
        S20["[0]"] --- S2d["…"] --- S29["[9]"]
    end

    L0 == overflowWheel ==> L1
    L1 -. "overflowWheel（惰性创建）" .-> L2

    subgraph B["单个槽位 TimerTaskList：带哨兵的环形双向链表"]
        direction LR
        R(("root")) <--> E1["Entry A"] <--> E2["Entry B"] <--> R
    end
    S02 -. 展开 .-> B

    subgraph SH["所有层共享"]
        direction LR
        Q["DelayQueue（元素为非空槽位）<br/>例：L0[2] → L0[5] → L1[1]<br/>按槽位 expirationMs 排序"]
        C[["taskCounter<br/>任务总数"]]
        K[["Clock"]]
    end
    L0 -.-> SH
    L1 -.-> SH
```
1.时间轮结构，类似于一个钟表，currentTick就是指针，表上的刻度就是槽位，一旦指针走到了哪个槽位，这个槽位的所有任务就到了执行时间， 因为每个刻度代表的时间有限，也意味着每个表代表的时间也是有限的，比如每个刻度代表1ms，一圈10个刻度也就能代表10ms，我如果要把11ms需要执行的任务怎么放进去呢？ 这里就到了overflow的概念了，如果本层轮放不下去，那就overflow到下一层的轮，下一层的每个刻度代表下层一整个表盘的时间长度，以刚才的例子为例，也就是说下一层一个刻度就是10ms，假设还是10个刻度，一共就是100ms， 如果时间再长，那就继续overflow到再下一层，直到整个表盘代表的时间溢出（long型）， 按照每个刻度1ms，每层表盘10个刻度，可以支撑2亿年之后的时间，实际上是足够了。
2.时间轮的0级时默认创建的，后面的overflow层都是有需要才创建的，时间轮本质上时一个数组，数组的元素时TimerTaskList，我们称之为桶，时间轮的大小再初始化时由输入确定
3. 不管task处于时间轮的那一层，这个task所在的桶的引用都保存在delayqueue， 按照过期时间有序排列

## 图 5：TimingWheel.add() 流程

```mermaid
flowchart TD
    A["add(entry)"] --> B{"任务已取消？"}
    B -- 是 --> R1["return false"]
    B -- 否 --> C{"expiration <= currentTimeMs？"}
    C -- 是 --> R2["return false<br/>（已到期，交给调用方执行）"]
    C -- 否 --> D{"roundUp？<br/>（只有第 0 层为 true）"}
    D -- 是 --> E["deadlineTick = ceil(exp / tick)"]
    D -- 否 --> F["deadlineTick = floor(exp / tick)"]
    F --> G{"deadlineTick <= currentTick？"}
    G -- 是 --> X["抛出 IllegalStateException<br/>（不变量被破坏）"]
    G -- 否 --> H
    E --> H{"deadlineTick < currentTick + wheelSize？"}
    H -- 是 --> I["放入 buckets[deadlineTick % wheelSize]"]
    I --> J{"setExpiration() 返回 true？"}
    J -- 是 --> K["queue.offer(bucket)"]
    J -- 否 --> L["return true"]
    K --> L
    H -- 否 --> M["overflow().add(entry)<br/>（递归交给上层轮）"]
```
1， 正常流程中任务被加入到时间轮的桶中后，如果桶已经在delayqueue了，就什么也不用做了，如果这个task是这个桶的第一个任务，那么加完时间轮后还要把任务所在的桶加到delayqueue中

## 图 6：任务添加和触发过程（DelayQueue 与多层时间轮，示例 expiration=105ms）

```mermaid
sequenceDiagram
    autonumber
    participant D as 到期处理<br/>poll → advanceClock → flush
    participant Q as DelayQueue
    participant L0 as L0<br/>tick=1ms
    participant L1 as L1<br/>tick=9ms
    participant L2 as L2<br/>tick=90ms

    Note over D,L2: t=0，添加任务，expiration=105
    L0->>L0: ceil(105/1)=105，超出 L0 范围（最大 tick 9）
    L0->>L1: overflow().add
    L1->>L1: floor(105/9)=11，超出 L1 范围（最大 tick 9）
    L1->>L2: overflow().add
    L2->>L2: floor(105/90)=1，放入 L2[1]
    L2->>Q: 槽位到期时间设为 90，offer(L2[1])

    Note over D,L2: t=90，DelayQueue 释放 L2[1]
    Q-->>D: L2[1] 的 getDelay ≤ 0
    D->>L0: advanceClock(90)，逐层传递，L0、L1、L2 都推进到 90
    D->>L2: flush L2[1]，取出任务
    D->>L0: 从最底层重新 add
    L0->>L0: ceil(105)=105，不小于 90+10，超出 L0 范围
    L0->>L1: overflow().add
    L1->>L1: floor(105/9)=11，在 (10, 20) 范围内，放入 L1[1]
    L1->>Q: 槽位到期时间设为 99，offer(L1[1])

    Note over D,L2: t=99，DelayQueue 释放 L1[1]
    Q-->>D: L1[1] 的 getDelay ≤ 0
    D->>L0: advanceClock(99)，L0、L1 推进到 99，L2 不变
    D->>L1: flush L1[1]，取出任务
    D->>L0: 从最底层重新 add
    L0->>L0: ceil(105)=105，小于 99+10，放入 L0[5]
    L0->>Q: 槽位到期时间设为 105，offer(L0[5])

    Note over D,L2: t=105，DelayQueue 释放 L0[5]
    Q-->>D: L0[5] 的 getDelay ≤ 0
    D->>L0: advanceClock(105)
    D->>L0: flush L0[5]，重新 add
    L0-->>D: expiration 105 ≤ currentTimeMs 105，返回 false
    Note over D: add 返回 false 表示任务到期，交给外层执行
```
1. 整个过程中多次出轮出queue， 因为涉及时间轮overflow和降级

## 图 7：TimingWheelTimer 结构

```mermaid
flowchart LR
    U(["调用方"]) -. "持有 TimerTask 句柄<br/>可调用 cancel()" .-> T

    subgraph T["TimingWheelTimer（对外入口，AutoCloseable）"]
        direction TB

        subgraph W["时间轮层级 wheel（只直接持有第 0 层）"]
            direction LR
            W0["TimingWheel L0<br/>buckets[wheelSize]"] ==>|overflowWheel| W1["L1"] -. 惰性创建 .-> W2["L2 …"]
        end

        subgraph IDX["到期索引与计数（传给所有层共享）"]
            direction LR
            DQ["delayQueue<br/>DelayQueue，元素为非空槽位"]
            TC[["taskCounter"]]
            CK[["clock"]]
        end

        subgraph LK["并发控制 rwlock（ReentrantReadWriteLock）"]
            direction LR
            RL["readLock<br/>添加任务一侧，可并发"]
            WL["writeLock<br/>推进时钟一侧，独占"]
        end

        subgraph EX["执行侧"]
            direction LR
            TE["executor<br/>TaskExecutor"]
            RH["rejectionHandler<br/>volatile，可为空"]
        end

        subgraph DR["驱动"]
            direction LR
            TH["driver 线程<br/>timewheel-driver（daemon）"]
            RN[["running（volatile）"]]
        end
    end

    W0 -. "非空槽位入队" .-> DQ
    TE -. "ThreadPoolTaskExecutor 包装" .-> EXT["外部 Executor<br/>调用方持有，timer 不负责关闭"]
```
1. TimingWheelTimer 是对外的入口，提供任务的schedule 和cancel等接口，同时持有驱动进程， 调用方可以把Executor作为参数传递进去
2. 并发控制：写锁独占执行任务出轮，此时禁止任务添加；写锁释放后，一般是阻塞在delayqueue上, 此时多个线程可以同时添加任务到时间轮，但是添加任务到一个桶或桶的一个节点需要串行化，
这里由桶的synchronized和节点的synchronized来保证。

## 图 8：TimingWheelTimer 读写锁与锁分层

```mermaid
flowchart LR
    subgraph CALLERS["持锁方"]
        direction TB
        S1["业务线程 1<br/>schedule()"]
        S2["业务线程 N<br/>schedule()"]
        D["driver 线程<br/>advanceClock()"]
        C["任意线程<br/>TimerTask.cancel()"]
    end

    subgraph RW["第 1 级：rwlock（Timer 级，ReentrantReadWriteLock）"]
        direction TB
        RL["readLock（共享）<br/>多个 schedule() 可同时持有"]
        WL["writeLock（独占）<br/>持有期间所有 schedule() 阻塞"]
        RL ---|"互斥"| WL
    end

    subgraph PROT["rwlock 保护的是：时间轮的“时间”"]
        direction TB
        P1["各层 TimingWheel.currentTimeMs<br/>add() 计算槽位期间不会被推进"]
        P2["槽位的到期时间与复用<br/>add() 期间槽位不会被 flush 后挪作下一圈"]
    end

    subgraph LOW["第 2、3 级：对象监视器（保护链表指针）"]
        direction TB
        BL["synchronized(TimerTaskList)<br/>槽位锁"]
        EL["synchronized(TimerTaskEntry)<br/>节点锁"]
        BL -->|"固定顺序：先槽位，后节点"| EL
    end

    S1 --> RL
    S2 --> RL
    D --> WL
    RL --> PROT
    WL --> PROT
    RL -->|"wheel.add → bucket.add"| BL
    WL -->|"bucket.flush → 摘除 / 重插"| BL
    C -. "不经过 rwlock，直接进入槽位锁" .-> BL
```
1.时间轮的槽位在是循环使用的，槽位的选择取决于两个因素currentTick 和 DeadlineTick，同样的到期时间，和目前时间在一个时间轮的时间跨度内，
就会在本层入轮， 否则就可能需要到其他层入轮； 在执行往桶中add()时，时间不可以推进， 否则就有可能把本轮需要触发的任务加到下一轮去，虽然是一个桶，
因为时间的推进，这个桶已经作为下一轮使用了。 这个机制的保证是add（）线程是持有读锁的，这时候算出的槽位是根据现在的时间和到期时间算出来的， 
这时候如果想推进时间需要获取写锁，需要等待读锁释放

## 图 9：并发控制，多个线程向同一槽位并发添加任务
```mermaid
sequenceDiagram
    participant T1 as 业务线程 1
    participant T2 as 业务线程 2
    participant B as 槽位 L0[5]<br/>到期时间 -1
    participant Q as DelayQueue

    Note over T1,T2: 两个线程都持有读锁，并行执行 add()<br/>都算出 deadlineTick=105，落到 L0[5]
    T1->>B: synchronized(槽位)，插入 entry A
    T2->>B: synchronized(槽位)，等待 T1 释放
    B-->>T1: 插入完成，释放槽位锁
    T1->>B: setExpiration(105)，getAndSet 返回 -1
    Note over T1: -1 ≠ 105，返回 true
    T1->>Q: offer(L0[5])
    B-->>T2: 获得槽位锁，插入 entry B
    T2->>B: setExpiration(105)，getAndSet 返回 105
    Note over T2: 105 = 105，返回 false，不重复 offer
```
1.添加任务的前提是首先目前写锁是释放的， 然后多个线程可以同时向桶里添加任务，同一个桶和同一个任务是通过synchronized保证串行化的。

## 图 10：ThreadPoolTaskExecutor 结构（两种模式）
```mermaid
flowchart LR
    TWT["TimingWheelTimer<br/>executor 字段"] --> TE

    subgraph TE["ThreadPoolTaskExecutor（实现 TaskExecutor）"]
        direction TB
        F1["delegate：Executor<br/>实际执行任务的线程池"]
        F2["owned：ExecutorService<br/>非 null 表示线程池归自己所有"]
        F3[["closed（volatile）"]]
    end

    subgraph M1["模式 A：包装调用方的 Executor<br/>new ThreadPoolTaskExecutor(executor)"]
        direction TB
        A1["delegate = 调用方传入的 Executor"]
        A2["owned = null"]
        A3["close() 只置 closed 标志<br/>不关闭线程池"]
    end

    subgraph M2["模式 B：自建线程池<br/>new ThreadPoolTaskExecutor(threads, capacity)"]
        direction TB
        B0["ThreadPoolExecutor（固定线程数）"]
        B1["ArrayBlockingQueue<br/>有界队列，容量 = capacity"]
        B2["NamedThreadFactory<br/>线程名 timewheel-worker-N"]
        B3["AbortPolicy<br/>队列满时立即拒绝"]
        B0 --- B1
        B0 --- B2
        B0 --- B3
        B4["delegate = owned = 该线程池<br/>close() 负责关闭"]
    end

    TE -. "二选一" .-> M1
    TE -. "二选一" .-> M2
```
1.任务执行器可以选择自建ThreadPool也可以从外部传入

## 图 11：调用关系（TimerTask 到 TimingWheelTimer）

```mermaid
flowchart TB
    U(["调用方"])
    DRV(["driver 线程<br/>timewheel-driver"])

    subgraph L4["外层：TimingWheelTimer"]
        TWT["TimingWheelTimer<br/>schedule / advanceClock<br/>addEntryOrFire / fire"]
        RH["rejectionHandler"]
    end

    subgraph L3["时间轮层"]
        TW["TimingWheel<br/>L0 → L1 → L2 …"]
        DQ["DelayQueue<br/>（元素为非空槽位）"]
    end

    subgraph L2["槽位层"]
        TTL["TimerTaskList<br/>add / remove / flush"]
    end

    subgraph L1["任务层"]
        TTE["TimerTaskEntry<br/>remove"]
        TT["TimerTask<br/>cancel / runIfInPending"]
    end

    subgraph L0["执行层"]
        TE["ThreadPoolTaskExecutor<br/>submit"]
        POOL["ThreadPoolExecutor<br/>或调用方的 Executor"]
        NTF["NamedThreadFactory"]
    end

    CK[["Clock.nowMs()"]]

    %% ① 添加任务
    U -->|"① schedule(delay, action)"| TWT
    TWT -->|"① new，构造时双向绑定"| TTE
    TWT -->|"① 持读锁 wheel.add"| TW
    TW -->|"① 超出本层范围：overflow().add"| TW
    TW -->|"① bucket.add"| TTL
    TW -->|"① 到期时间变化时 offer"| DQ

    %% ② 推进时钟与到期执行
    DRV -->|"② advanceClock(100ms)"| TWT
    TWT -->|"② poll（在锁外阻塞等待）"| DQ
    TWT -->|"② 持写锁 advanceClock(exp)"| TW
    TWT -->|"② flush(addEntryOrFire)"| TTL
    TTL -->|"② 回调：未到期则重插，到期则 fire"| TWT
    TWT -->|"② submit"| TE
    TE -->|"② execute(runIfInPending)"| POOL
    POOL -->|"② CAS 成功后执行 action"| TT
    TE -. "② 返回 false" .-> RH
    NTF -.->|"创建守护线程"| POOL

    %% ③ 取消任务
    U -->|"③ cancel()"| TT
    TT -->|"③ CAS 成功后 entry.remove"| TTE
    TTE -->|"③ 循环 list.remove"| TTL

    CK -.-> TWT
    CK -.-> TTL
```
1. @1 添加任务 @2 时间推进与执行  @3 取消任务

## 图 12：DurableScheduler 的数据分布（以任务 order-123 为例）

```mermaid
flowchart LR
    U(["调用方<br/>schedule(order-123, 5000ms, payload)"])
    UC(["调用方<br/>cancel(order-123)"])

    subgraph DISK["① TaskStore：持久化，重启后仍在"]
        direction TB
        A1["活动记录<br/>id = order-123<br/>deadline = 10:00:05（epoch 时间）<br/>attempts = 0"]
        DL1["死信记录<br/>id = pay-9<br/>attempts = 3，lastError = …<br/>只存在于存储中，等待人工处理"]
    end

    subgraph BRIDGE["② DurableScheduler：进程内"]
        direction TB
        LIVE["live 映射<br/>order-123 → TimerTask t7"]
    end

    subgraph MEM["③ 时间轮：进程内，重启即丢失"]
        direction TB
        TT["TimerTask t7<br/>还剩 5000ms（单调时钟）"]
        TR["TaskRunnable<br/>携带 order-123 记录的副本"]
        TT -->|"action"| TR
    end

    U -->|"调度 1：putIfAbsent，先写入记录"| A1
    U -->|"调度 2：写入成功后 arm，放入时间轮"| TT
    U -->|"调度 3：登记 id → TimerTask"| LIVE

    UC -->|"取消 1：remove，先删除记录"| A1
    UC -->|"取消 2：live.remove，取出 TimerTask"| LIVE
    UC -->|"取消 3：task.cancel，从时间轮摘除"| TT

    TR -. "到期执行前，先确认记录仍在" .-> A1
    A1 -. "recover 时：读取记录，重做调度 2、3" .-> TT

    CR["进程崩溃或重启：② ③ 清空，① 保留<br/>recover() 读取 ① 中的活动记录，重建 ② 和 ③"]
    DISK ~~~ CR

    classDef note stroke-dasharray: 5 5
    class CR note
```
1. taskstore 是任务的持久化层，用于存储活动task 和死信记录
2. 恢复时先从taskstore 恢复， taskstore 可以有多种实现方式，可以是RocksDB,也可以是Redis
3. 
## 图 13：TaskStore 中记录的生命周期

```mermaid
stateDiagram-v2
    direction LR
    state "活动记录 Active" as Active
    state "死信 DeadLetter" as DeadLetter

    [*] --> Active: putIfAbsent()，schedule 调用
    Active --> Active: replace()，失败重试时 attempts+1 并更新 deadline
    Active --> [*]: remove()，执行成功或 cancel
    Active --> DeadLetter: deadLetter()，原子移动，覆盖同 id 的旧死信
    DeadLetter --> Active: requeueDeadLetter()，attempts 清零
    DeadLetter --> [*]: removeDeadLetter()，人工丢弃

    note right of Active
        loadAll() 读取全部活动记录（recover 时使用）
        contains() 判断 id 是否仍然有效
    end note

    note right of DeadLetter
        loadDeadLetters() 供人工查看
        同一个 id 可以同时存在于两个空间：
        进入死信后又被重新 schedule
    end note
```
1. 任务入轮是被拒绝，继续尝试，入轮时间加抖动
2. 任务执行失败，更新到期时间，更新数据库记录，从新入轮， 如果失败多次达到配置的失败次数，进入死信队列，
3. 死信队列的内容需要运维接入，提供重新放入数据库和入轮的接口


## 图 14：整体调用关系（DurableScheduler 持久化层）

```mermaid
flowchart TB
    U(["调用方"])
    W(["工作线程"])

    subgraph DSL["持久化层：DurableScheduler"]
        DS["schedule / cancel / recover<br/>retryDeadLetter"]
        DP["dispatch<br/>（TaskRunnable.run）"]
        OF["onFailure"]
        OR["onRejected"]
        ARM["arm<br/>timer.schedule + live.put"]
    end

    ST[("TaskStore<br/>活动记录 / 死信")]
    H["TaskHandler<br/>业务处理"]
    TWT["TimingWheelTimer 及以下各层<br/>（见图 9）"]

    U -->|"A schedule"| DS
    DS -->|"A ① putIfAbsent"| ST
    DS -->|"A ② 写入成功后"| ARM
    ARM -->|"schedule(delay, TaskRunnable)"| TWT

    U -->|"B recover（启动时一次）"| DS
    DS -->|"B loadAll，按 deadline 排序"| ST
    DS -->|"B 逐条"| ARM

    TWT -->|"C 到期后提交给线程池"| W
    W -->|"C TaskRunnable.run"| DP
    DP -->|"C ① contains"| ST
    DP -->|"C ② handle"| H
    DP -->|"C ③ 成功：remove"| ST
    DP -->|"C ③ 失败"| OF
    OF -->|"可重试：replace 后"| ARM
    OF -->|"耗尽或不可重试：deadLetter"| ST

    TWT -.->|"D 线程池拒绝：rejectionHandler"| OR
    OR -.->|"D 不计入重试，延迟约 200ms"| ARM

    U -->|"E cancel"| DS
    DS -->|"E ① remove"| ST
    DS -->|"E ② live.remove → task.cancel"| TWT
```

1.A 调度	先写存储，再放入时间轮	崩溃时任务留在存储里，恢复后重新放入时间轮
2.B 恢复	加载全部活动记录，已过期的立即执行	必须在调度之前调用
3.C 执行	检查记录 → 执行业务 → 根据结果删除、重试或转入死信	执行完才删记录，崩溃时任务会重复执行
4.D 拒绝	线程池拒绝，说明业务没有执行，稍后重投	不消耗重试次数
5.E 取消	先删存储，再取消时间轮里的任务	崩溃时被取消的任务不会复活

## 图 15：时间轮启动与任务唤醒机制（启动 → 等待 → 唤醒 → 执行 → 关闭）

```mermaid
sequenceDiagram
    autonumber
    participant U as 调用方
    participant T as TimingWheelTimer
    participant D as driver 线程
    participant Q as DelayQueue<br/>lock + available
    participant W as TimingWheel
    participant E as 线程池

    Note over U,E: 启动阶段
    U->>T: new TimingWheelTimer(1ms, 10, executor)
    Note over T: 创建 DelayQueue 和第 0 层时间轮<br/>此时还没有线程在推进时间
    U->>T: start()
    T->>D: running = true，创建并启动 daemon 线程
    D->>T: driverLoop：立即调用 advanceClock(100)

    Note over U,E: t=0，队列为空
    D->>Q: poll(100ms)
    Q->>Q: 队列为空，available.awaitNanos(100ms)
    Note over D: 释放 lock，在 available 上休眠

    Note over U,E: t=100，等待超时
    Q-->>D: 返回 null
    D->>W: 写锁内 advanceClock(nowMs=100)
    D->>Q: 下一轮 poll(100ms)，最多等到 t=200

    Note over U,E: t=110，调度 delay=10ms 的任务，到期时间 120
    U->>T: schedule(10, action)
    T->>W: 读锁内 add：超出 L0 范围，放入 L1 槽位（到期 117）
    W->>Q: offer(槽位)
    Q->>Q: 新槽位成为队首，available.signal()
    Q-->>D: 唤醒 driver
    D->>Q: 重新计算 117 - 110 = 7ms，awaitNanos(7ms)

    Note over U,E: t=117，L1 槽位到期
    Q-->>D: 返回 L1 槽位
    D->>W: 写锁内 advanceClock(117)，flush
    W->>W: 任务重新 add，降级到 L0 槽位（到期 120）
    W->>Q: offer(L0 槽位)
    D->>Q: 无参 poll() 排空，没有其他到期槽位，返回 null
    D->>W: advanceClock(nowMs)，释放写锁
    D->>Q: 下一轮 poll(100ms)，队首还差 3ms，awaitNanos(3ms)

    Note over U,E: t=120，L0 槽位到期
    Q-->>D: 返回 L0 槽位
    D->>W: 写锁内 advanceClock(120)，flush
    W-->>D: 重新 add 返回 false，任务到期
    D->>E: fire → submit(runIfInPending)
    E->>E: 工作线程执行 action
    D->>Q: 下一轮 poll(100ms)，队列为空，继续休眠

    Note over U,E: 关闭阶段
    U->>T: close()
    T->>D: running = false，interrupt()
    D-->>T: awaitNanos 抛出 InterruptedException，driverLoop 退出
```
1.启动：构造函数只创建数据结构，不会推进时间。start() 之后 driver 线程立即进入 advanceClock()，并停在 poll(100ms) 里等待，中间没有任何前置延迟。
2.空闲等待：队列为空时，driver 在 available 上最多休眠 100ms，超时后把时间轮推进到当前时间，然后再次等待。
3.新任务唤醒：offer() 让新槽位成为队首时，通过 available.signal() 唤醒 driver，driver 按新队首的剩余时间（7ms）重新等待，而不是等到 t=200。
4.跨层降级：t=117 是第 1 层槽位到期，任务只是被降级到第 0 层，并没有执行；t=120 第 0 层槽位到期，重新 add 返回 false，才真正提交给线程池执行。
5.关闭：close() 先把 running 置为 false，再中断 driver。driver 正在 awaitNanos 中休眠时会立刻抛出 InterruptedException，driverLoop 捕获后直接返回，不需要等到 100ms 超时。
