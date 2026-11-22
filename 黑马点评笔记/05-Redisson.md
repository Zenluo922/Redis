# 黑马点评笔记 05：分布式锁进阶——Redisson（可重入 / 看门狗 / 联锁）

> 对应实战篇 05 章（ch5.1~5.5）。**04 篇手写锁的三个窟窿，Redisson 每个特性都是对着补的**——学的时候反复对照"手写版做不到 → Redisson 怎么做到"，这条线顺了就全懂了。
> 完成日期：2026-11-22 ｜ 项目代码：`D:\Develop\code\hm-dianping\`
> 前置：`04-分布式锁.md`（手写 SETNX 锁 + Lua 释放）。

---

## 〇、全景：三个窟窿 → 三件武器（对照着记）

| 04 篇手写版的遗留问题 | 场景 | Redisson 对应武器 | 本篇章节 |
|---|---|---|---|
| 不可重入 | 同一线程已持锁，内层方法又想拿同一把锁 → 等自己释放 → 死等 | 可重入锁（Hash 结构 + 计数） | 5.3 → 第二节 |
| 业务超时锁先丢 / 无续期 | TTL 定死了，业务没跑完锁先过期（互斥窗口失守）；TTL 设太长宕机后别人干等 | watchdog 看门狗自动续期 | 5.4 → 第三节 |
| 主从切换锁丢失 | 主节点加锁成功，还没同步到从就宕机，新主上锁凭空消失 | MultiLock 联锁（多独立节点各存一把） | 5.5 → 第四节 |

**一句话总结**：手写 SimpleRedisLock 是自己拿扳手攒了一把锁（三要素：SETNX 互斥、TTL 防死锁、标识防误删），Redisson 是一套精装修的智能锁——不仅三要素都有，还送了三样手写版给不了的：**重入、续期、跨节点**。

---

## 一、Redisson 是什么 + 项目接入（ch5.1~5.2）

### 1.1 定位

Redisson 是一个 **Redis 的 Java 客户端 + 分布式工具集**。跟 StringRedisTemplate 不是竞品关系：模板只给你"发命令的原语"，Redisson 在原语之上封装了**分布式对象**（锁、Map、限流器、布隆过滤器……）。我们用的就是它的 `RLock`。

### 1.2 接入（本项目真实配置，含两个版本坑）

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.50.0</version>
    <exclusions>
        <!-- starter 自动选了 spring-data-35，与 Boot 3.3.5（spring-data-redis 3.3.x）不匹配 -->
        <exclusion>
            <groupId>org.redisson</groupId>
            <artifactId>redisson-spring-data-35</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-data-33</artifactId>
    <version>3.50.0</version>
</dependency>
```

> 🅱️ **两个真实版本坑**：① 课程用的 3.13.6（2021 年）不认识 Boot 3 的 `spring.data.redis` 前缀，必须升新版；② 最新 starter 会自动拉 `redisson-spring-data-35` 适配模块，和 Boot 3.3 的 spring-data-redis 3.3.x 版本错位，运行时可能 `NoSuchMethodError`——手动 exclusion + 指定 33。
> 配置不用写 yaml：starter 自动读 `spring.data.redis`（host/密码已在 application.yaml 里）。

`RedissonConfig` 里那个 `redissonClient()`（`useSingleServer` 指到 6379）和 starter 二选一即可：你自己定义了 `RedissonClient` bean，自动配置就退避。**业务只用一把锁时不需要额外配置类**。

> ⚠️ **启动炸预警（差点踩）**：`RedissonConfig` 里 6380/6381 两个 bean 是 **MultiLock 演示专用**（第四节），`Redisson.create()` 启动时会真的去连接——虚拟机上没起这两个实例，应用直接启动失败（RedisConnectionException）。已注释，学到 5.5 再启用。

### 1.3 入门用例（手写版 → Redisson 对照）

```java
// 手写版（04 篇）
SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
boolean isLock = lock.tryLock(1200);
...
lock.unlock();

// Redisson 版
RLock lock = redissonClient.getLock("lock:order:" + userId);   // getLock 只是造对象，不碰 Redis
boolean isLock = lock.tryLock();                                // 无参 = 不重试 + watchdog（见第三节）
...
lock.unlock();
```

`getLock("名字")` 相同名字 = 同一把锁（key 就是锁名），和手写版 KEY_PREFIX + name 一个道理。

---

## 二、可重入锁原理（ch5.3）★ 必考

### 2.1 什么是可重入，为什么手写版不行

**可重入**：同一线程已经拿着锁，再次获取同一把锁时不阻塞，而是"再进一次"。

**手写版为什么不可重入**：锁就是一个 String key。线程 1 第一次 `setnx` 成功；它再次 `setnx` 时 key 已存在 → 失败。白话：**你已经在房间里了，再去推门，门上写着"里面有人"，而"那个人"就是你自己**——你在门外等你自己开门，永远等不到。

**为什么业务需要**：方法层层调用都可能要锁（`a()` 加锁后调 `b()`，`b()` 里也要同一把锁）。Java 的 `synchronized` 天然可重入（锁对象上记了持有线程 + 重入计数），分布式锁理应对齐。

### 2.2 Redisson 的数据结构：从 String 换成 Hash

```
手写版：  lock:order:1  →  "uuid-15"                 （String：只有"谁持有"）
Redisson：lock:order:1  →  { "uuid-15": 2 }          （Hash：谁持有 + 重入了几次）
          key = 锁名   field = 线程标识   value = 重入计数
```

**为什么必须是 Hash**：String 只能存一个值，"我持有这把锁"和"我进了几次"两个信息放不下。Hash 的 field 存线程标识、value 存计数——重入一次 +1，退出一次 -1，减到 0 才真删锁。

> 线程标识还是 `UUID:threadId`，和 04 篇手写版同一个设计——跨 JVM 唯一 + 分线程。**你已经懂的部分，Redisson 原样在用。**

### 2.3 加锁的 Lua 逻辑（逐行读懂）

```lua
if (redis.call('exists', KEYS[1]) == 0) then          -- 锁还不存在
    redis.call('hincrby', KEYS[1], ARGV[2], 1);       -- 建hash，我的field计数=1（首次拿锁）
    redis.call('pexpire', KEYS[1], ARGV[1]);          -- 设置过期时间
    return nil;                                        -- 返回nil = 成功
elseif (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then  -- 锁存在，且field是我自己
    redis.call('hincrby', KEYS[1], ARGV[2], 1);       -- 计数+1（重入！不用等）
    redis.call('pexpire', KEYS[1], ARGV[1]);          -- 刷新过期时间
    return nil;                                        -- 成功
end;
return redis.call('pttl', KEYS[1]);                    -- 是别人的锁：失败，返回剩余过期时间
```

**白话比喻**：房间门牌写"张三 1"；张三再进来，门牌改"张三 2"；又进来改"张三 3"。别人来看：门牌不是我的名字 → 拿到的是"门牌剩余时间"（给重试逻辑用，见第三节）。

### 2.4 解锁的 Lua 逻辑（对称着记）

```lua
if (redis.call('hexists', KEYS[1], ARGV[3]) == 0) then   -- 不是我的锁
    return nil;                                            -- 不能删（防误删，同04篇思想）
end;
local counter = redis.call('hincrby', KEYS[1], ARGV[3], -1);  -- 计数-1
if (counter > 0) then                                     -- 还有没退完的重入
    redis.call('pexpire', KEYS[1], ARGV[2]);              -- 只刷过期时间，不删锁
    return 0;
else
    redis.call('del', KEYS[1]);                           -- 退干净了，真删锁
    redis.call('publish', KEYS[2], ARGV[1]);              -- 广播"解锁了"（叫醒等待重试的线程）
    return 1;
end;
```

出门一次门牌 -1：3 → 2 → 1 → **0 时摘牌**，并广播"房间空了"——等待的线程靠**订阅**收到消息来重试，而不是傻轮询。

> **温馨小贴士：为什么加锁解锁也要 Lua**——和 04 篇完全一样的理由！"判断 field 是不是我"和"hincrby"是两条命令，不原子就会误删/错加。你已经掌握的"组合命令不原子 → Lua"在这里原样复用。

---

## 三、锁重试 + watchdog 自动续期（ch5.4）★ 必考

### 3.1 tryLock 的两个参数语义

```java
boolean tryLock(long waitTime, long leaseTime, TimeUnit unit)
//              最多等多久       拿到的锁多久过期
boolean tryLock()   // 等价于 tryLock(0, -1)：不等待 + 启用watchdog
```

### 3.2 重试逻辑（waitTime > 0 时）

抢不到锁**不是立刻放弃，也不是傻轮询**：

```
抢锁 → 失败 → 订阅"锁名"频道 + 等待（剩余waitTime）
        ↓ 收到解锁广播
     再抢 → 失败 → 再等剩余waitTime → ……
        ↓ waitTime 耗尽
     返回 false
```

精髓在**订阅**：删锁的人会 publish 广播，等待的人被"叫醒"而不是反复白问 Redis——省掉了无效轮询。

### 3.3 watchdog（看门狗）：解决"业务超时锁先丢"

手写版的死结：TTL 设 10s，业务跑了 12s → 锁提前没了；TTL 设 60s，线程在第 5s 宕机 → 其他请求干等 55s。

**watchdog 的做法**（只在 `leaseTime = -1`，即不手动指定过期时间时启用）：

- 加锁成功 → 锁的过期时间默认 **30s**；
- 后台起一个定时任务，**每 10s（1/3）把锁续回 30s**；
- 线程活着业务没完 → 锁永不过期但一直有人管；线程死了 → 没人续期 → **30s 后锁照常过期**，不会永久锁死。

**为什么是 1/3**：容错余量——即使续期连续失败两次，锁还没过期，第三次还能补上。

**watchdog 什么时候停**：`unlock()` 时顺带 cancel 定时任务。所以**必须 finally unlock**，否则 watchdog 一直给一把已经该释放的锁续命。

> ⚠️ **最容易误解的点**：一旦显式传了 `leaseTime > 0`（比如 `tryLock(10, 30, SECONDS)`），**watchdog 就不启用**——锁就是普通过期制，到点就死。想要自动续期就不要指定 leaseTime。本项目 `handleVoucherOrder` 用的正是无参 `tryLock()`（启用 watchdog）。

**手写版 vs watchdog 对照**（面试金句素材）：

| | 手写版 TTL | Redisson watchdog |
|---|---|---|
| 业务没跑完 | 锁到点就丢，互斥失守 | 持续续期，锁一直在 |
| 线程宕机 | 等 TTL 到期（设多长等多久） | 最多 30s（没人续期自然过期） |
| 本质 | 一刀切的定时炸弹 | "心跳"——活着就续，死了自然过期 |

---

## 四、MultiLock 联锁（ch5.5）

### 4.1 它解决什么：主从切换锁丢失

Redis 主从是**异步复制**。极端时序：客户端在**主**上加锁成功 → 主还没来得及同步给从就宕机 → 哨兵把**从**提升为新主 → 新主上根本没有这把锁 → 另一个客户端再加锁成功 → **两个客户端同时持锁，互斥失效**。

### 4.2 思路：不靠复制，靠冗余投票

在**多个互相独立的主节点**（不是主从关系，就是 6379/6380/6381 三个单机）上**各存一把锁**，**全部成功才算加锁成功**；任何一个没锁上，就把已加的全释放（保证不留半把锁）。白话：钥匙要同时插进三个保险柜，三个都锁上才算锁了；开锁三个都开。

```java
RLock lock1 = redissonClient.getLock("lock");    // 6379
RLock lock2 = redissonClient2.getLock("lock");   // 6380
RLock lock3 = redissonClient3.getLock("lock");   // 6381
RedissonMultiLock multiLock = new RedissonMultiLock(lock1, lock2, lock3);
multiLock.lock();
```

代价：加锁成本 ×3、任一节点故障都锁不上（牺牲可用性换安全性）。

### 4.3 一笔带过但面试加分：RedLock 争议

这套"多节点各存一把"的思想源于 Redis 官方提出的 **RedLock 算法**，但分布式领域大神 Martin Kleppmann（《DDIA》作者）撰文质疑其安全性，与 Redis 作者 antirez 展开论战。面试能提一句"RedLock 存在争议，实践中 Redisson 的 MultiLock / 或改用 Zookeeper 锁更稳"就是加分项。

### 4.4 本项目演示步骤（学到 5.5 时照做）

```bash
# 虚拟机上临时起两个 Redis 实例（不生产常驻）
redis-server --port 6380 --daemonize yes
redis-server --port 6381 --daemonize yes
# 然后 RedissonConfig 里取消 redissonClient2/3 的注释，重启后端
# 演示完：redis-cli -p 6380 shutdown；配置注释回去
```

---

## 五、项目接入现状

`VoucherOrderServiceImpl.handleVoucherOrder`（异步下单消费者里，详见 06 篇）：

```java
RLock lock = redissonClient.getLock("lock:order:" + userId);
boolean isLock = lock.tryLock();      // 无参：不重试 + watchdog 续期
if (!isLock) { log.error("不允许重复下单"); return; }
try {
    proxy.createVoucherOrder(voucherOrder);
} finally {
    lock.unlock();                    // 必须finally：解锁同时停watchdog
}
```

**为什么这里 tryLock() 不传 waitTime**：一人一单语义下，同一用户并发第二单直接拒绝比排队等待合理（04 篇追问链同款答案）。手写版 `SimpleRedisLock` + `unlock.lua` 保留在项目里当学习标本——简历/面试可以讲"手写演进到 Redisson"全过程，比直接用 Redisson 的人多一层理解。

---

## 六、★ 本模块踩坑记录（2026-11-22）

| # | 坑 | 现象 | 修复/预防 |
|---|---|---|---|
| 1 | 课程依赖 3.13.6 不支持 Boot3 | 读不到 `spring.data.redis` 配置 | 升 3.50.0 |
| 2 | starter 自动选错适配模块 | 拉了 `redisson-spring-data-35`，Boot 3.3 是 3.3.x | exclusion 35 + 显式加 33 |
| 3 | MultiLock 演示 bean 提前抄进来 | `Redisson.create()` 启动连不上 6380/6381 → **应用启动直接失败** | 注释保留，学 5.5 时启用+临时起实例 |
| 4 | 旧 IDEA 实例占着 8081 | 改完代码验证，结果全是**旧代码的行为**（报错文案都是旧的），一度以为新代码有问题 | `netstat -ano \| findstr :8081` 查 PID → taskkill → 重启。**教训：端口被旧实例占着 = 你在测一个不存在的版本** |

---

## 七、八股结合点

1. **可重入锁的实现**：synchronized（Monitor 记持有线程+计数）、ReentrantLock（AQS state 计数）、Redisson（Hash field+value 计数）——**同一思想三种载体**，串着答很加分。
2. **Redisson watchdog**：leaseTime=-1 默认 30s、每 1/3（10s）续期、unlock 时 cancel、指定 leaseTime 则不启用——四件套背熟。
3. **发布订阅**：Redis PubSub 在这里的角色是"解锁广播"（避免轮询）；延伸：PubSub 不持久化（离线收不到）→ Stream 才有消费者组（ch7 会学，先挂个钩子）。
4. **主从复制是异步的** → 极端时序锁丢失 → MultiLock/RedLock → RedLock 争议（Kleppmann vs antirez）→ 工程折中。
5. **分布式锁选型**：Redisson（性能高，AP 倾向）vs Zookeeper 临时顺序节点（CP，更稳但慢）——一句话答法："对性能敏感用 Redisson，对正确性极端敏感（如选主）考虑 ZK"。

---

## 八、面试题库

### 🔥 必背五题

1. **Redisson 可重入锁怎么实现的？** Hash 结构：key=锁名、field=线程标识（UUID:threadId）、value=重入计数。加锁 Lua：锁不存在 hset 计数 1；存在且 field 是自己计数 +1（重入）；否则失败返回剩余 TTL。解锁对称：计数 -1，大于 0 只刷过期时间，等于 0 才 del 并广播解锁。全程 Lua 保证"判断+操作"原子。
2. **watchdog 是什么？解决什么问题？** 手写锁 TTL 定死：设短了业务没完锁先丢，设长了宕机后别人干等。watchdog 在 leaseTime=-1 时启用：锁默认 30s，后台任务每 10s（1/3，容错余量）续回 30s；线程活着一直续，线程死了没人续 → 30s 自然过期不会永久锁死；unlock 时 cancel。**一旦手动指定 leaseTime 就不启用**。
3. **主从切换锁丢失怎么回事？怎么解决？** 异步复制：主加锁成功未同步即宕机 → 从升主后锁消失 → 双客户端同时持锁。解法 MultiLock：多个独立主节点各存一把，全成功才算锁成功（RedLock 思想，存争议——Kleppmann 质疑过，极端正确性场景可上 ZK）。
4. **tryLock 的参数含义？** waitTime：抢不到最多等多久（期间订阅解锁消息被叫醒后重试，非轮询）；leaseTime：锁的租期，>0 则到期失效且无 watchdog；无参 tryLock() = tryLock(0, -1)：不等待 + watchdog。
5. **为什么用 Redisson 替代手写？** 手写三要素齐但三缺陷：不可重入（线程等自己死锁）、无续期（TTL 死结）、主从丢锁；Redisson 分别用 Hash 计数、watchdog、MultiLock 补齐，且加解锁全程 Lua 原子、解锁广播替代轮询。我在项目里两版都写过（SimpleRedisLock → RLock），演进理由能说全。

### ⚡ 追问链

- 可重入 →"重入计数存在 JVM 里行不行？"（不行，锁在 Redis 上，计数必须跟着锁走）
- watchdog →"续期任务在哪个线程？服务停了怎么办？"（Netty 定时任务；服务停了没人续期 → 30s 自然过期，不会永久死锁）
- 联锁 →"三个节点挂了一个还能锁吗？"（不能，全成功语义——牺牲可用性保安全）
- 广播 →"PubSub 消息丢了怎么办？"（等待方有超时兜底会重新查询；要可靠消息用 Stream——ch7）
- 选型 →"Redisson 锁和 synchronized 区别？"（JVM 内 vs 跨进程；Monitor vs Redis+Lua；不可中断 vs 可设 waitTime/watchdog）

### 🎨 白板手画清单

- Hash 可重入结构：key/field/value + 重入计数变化（1→2→1→0删锁）
- watchdog 时间线：0s 加锁 30s → 10s 续 30s → 20s 续 30s → …（线程死亡 → 最后一次续期后 30s 到期）
- 主从锁丢失时序图（加锁→宕机→升主→二次加锁）

---

## 九、自测清单（口述过关标准）

- [ ] 背出 04 篇三遗留问题 → Redisson 三武器对应关系
- [ ] 白话解释"不可重入 = 线程等自己"，画出 Hash 结构和计数变化
- [ ] 默写加锁/解锁 Lua 逻辑的关键分支（exists / hexists / hincrby / pexpire / del / publish）
- [ ] 说清 watchdog 四件套：默认 30s、每 1/3 续期、unlock 停、指定 leaseTime 不启用
- [ ] 说清 tryLock(waitTime, leaseTime) 两参数语义与订阅唤醒重试
- [ ] 口述主从锁丢失时序 + MultiLock 思路 + RedLock 争议一句话
- [ ] 说出本项目 Redisson 两个版本坑（3.13.6 不支持 Boot3 / spring-data-33）
- [ ] 说出 MultiLock 演示的前置条件（6380/6381 临时实例，否则启动炸）

---

## 十、参考资料

- 课程：黑马点评实战篇 05 分布锁-redission（ch5.1~5.5）
- 本仓库实战笔记：`Redis\02-实战篇\实战笔记\Redis实战篇.md` 第 5 章
- 代码：`pom.xml`（redisson 依赖）、`config/RedissonConfig.java`、`service/impl/VoucherOrderServiceImpl.java`
- 关联笔记：`04-分布式锁.md`（手写版，本篇的一切对照物）、`06-秒杀优化-异步秒杀上.md`（Redisson 在异步链路里的使用位置）
