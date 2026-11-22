# 黑马点评笔记 07：Redis 消息队列 + Stream 版异步秒杀（终版）

> 对应实战篇 07 章（ch7.1 认识消息队列 → 7.2 List 版 → 7.3 PubSub 版 → 7.4 Stream → 7.5 消费者组 → 7.6 Stream 改造秒杀）。**至此秒杀模块终版完成**：Lua 判断资格 + Stream 消息队列 + 消费者组 + PEL 故障恢复。
> 完成日期：2026-11-22（Claude 检查补齐 + E2E 验证通过）｜ 项目代码：`D:\Develop\code\hm-dianping\`
> 前置：`06-秒杀优化-异步秒杀上.md`（〇.5 概念垫底 / 阻塞队列版 / 三宗罪）。**没看懂 06 篇 〇.5 节先回去看，本篇全部建立在它上面。**

---

## 〇、全景：三宗罪 → 三个候选 → Stream 胜出

```
06 篇阻塞队列版的三宗罪：
  ① 内存有界（满了丢单）  ② 不可持久化（宕机全丢，用户已收到成功响应）  ③ 不能跨 JVM（集群队列不共享）
      ↓ 病根：队列是 JVM 内存里的对象，出餐台长在 Tomcat 身上
ch7 的三个候选方案（全是拿三宗罪当评分表）：
  7.2 List 版：LPUSH+BRPOP           → 还是丢消息（无ACK）、无消费组
  7.3 PubSub 版：广播，听不到就没     → 不持久化、离线收不到、堆积丢
  7.4/7.5 Stream 版 ★               → 持久化在 Redis + 消费者组 + ACK/PEL 故障恢复
7.6：用 Stream 重写异步秒杀 = 本篇
```

**一句话总结**：消息队列就是把 06 篇那张"出餐台"**从 JVM 内存里搬到 Redis 服务器上**——台子不在任何一台 Tomcat 身上了（解决③），台子上的单据写进磁盘不怕断电（解决②），台子大小可配（解决①）。后面的 List/PubSub/Stream 都是"台子的三种材质"，Stream 是唯一"单据记账 + 干完打勾"的。

**消息队列三大好处**（八股标准答案，对应到秒杀）：**异步**（发消息就返回，不等消费完）、**解耦**（下单和写库不互相依赖，写库挂了不影响接单）、**削峰**（瞬时 1 万单先排队落盘，后台按数据库的节奏慢慢消费）。

---

## 一、先建立心象：Stream 长什么样

**Stream = 只追加的日志（Append-Only Log）+ 组长记账本（PEL）**。

```
stream.orders  ────────────────────────────────────────────→ 时间轴
   [1-1: 单A]  [1-2: 单B]  [1-3: 单C]  [1-4: 单D]
                    ↑ 组 g1 的记账本（PEL/Pending Entries List）：
                      "1-2 我分给 c1 了，他还没回话（没ACK）"
```

- 每条消息有全局唯一 id（`毫秒时间戳-序号`，如 `1712345678901-0`，`*` = 让 Redis 自动生成）；
- 消息**写进去就一直躺着**（ACK 只是记账本打勾，不是撕掉单据——所以 ACK 后 `XLEN` 不会变小，这是很多人第一次都懵的点）；
- 消费者组 g1 下的多个消费者**竞争**同一批消息（一条消息只给组内一个人），天然支持多机集群消费。

---

## 二、三个候选方案精讲（ch7.2~7.5，评分表对照三宗罪）

### 2.1 List 版（LPUSH + BRPOP）：无盖传送带

```bash
生产者：LPUSH orders 订单JSON      # 左进
消费者：BRPOP orders 10           # 右出，没有则阻塞10秒
```

- ✅ 简单、能阻塞（BRPOP 解决"空转"）、跨进程（数据在 Redis）；
- ❌ **取走即丢**：BRPOP 弹出后消息就没了——消费者拿到消息后服务崩溃，消息无处可寻（**没有 ACK/记账机制**）；
- ❌ 无消费者组：多台机器 BRPOP 就是纯竞争，消息被谁拿走、处理没处理，无人知晓；
- 评分：宗罪①半解（可配 MAXLEN 截断）、②未解（取走即丢=另一种丢法）、③已解。

### 2.2 PubSub 版（发布订阅）：广播电台

```bash
SUBSCRIBE order.channel   # 订阅频道（收音机调频）
PUBLISH order.channel xx  # 往频道广播（电台播音）
```

- ✅ 能广播（一条消息多个接收方——List/Stream 都做不到）、能阻塞监听；
- ❌ **不持久化**：消息发出去没人在线收，就永远没了；消费端离线期间的message 全丢；堆积会丢（Redis 对 PubSub 缓冲区有上限，超了踢连接）；
- 评分：只解决了③，②比 List 还差。**适合"丢了无所谓"的场景**（如在线状态推送），不适合订单。

### 2.3 Stream 版 ★：带记账本的操作日志

| 能力 | 说明 | 对应命令 |
|---|---|---|
| 持久化 | 消息躺在 Redis 内存（可配 RDB/AOF 落盘），不随消费者生死 | XADD |
| 阻塞读 | 没消息时消费者挂起等待 | XREAD BLOCK |
| 回溯 | 新来的消费者能从 0 开始读历史消息（PubSub 做不到） | XREAD + id |
| 消费者组 | 组内竞争消费（负载均衡）、组间独立消费（广播效果） | XGROUP/XREADGROUP |
| ACK 记账 | 处理完打勾；没打勾的留在 PEL 里可重取——**故障恢复的根基** | XACK / XPENDING |
| ⚠️ 局限 | 单机 Redis（无集群复制保障）、默认无 MAXLEN 会无限堆积、无死信队列 | — |

**结论**：Stream 是 Redis 里最接近专业 MQ 的结构；三宗罪逐条对症（①MAXLEN 限长、②持久化+PEL、③数据在 Redis 服务器）。

---

## 三、Stream 命令族速查 + 游标辨析（VM 里实操过的都标了）

| 命令 | 作用 | 我踩过的 |
|---|---|---|
| `XADD key * f1 v1 f2 v2` | 追加消息，`*` 自动生成 id | 放进 Lua 里和资格判断原子执行（7.6 核心） |
| `XLEN key` | 流里**还躺着**多少条（**ACK 不减少它！**） | 第一次看到 ACK 后 XLEN 不变都懵——ACK 只动记账本 |
| `XGROUP CREATE key 组名 0 MKSTREAM` | 建消费组；`0`=从头消费历史，`$`=只消费新建后的；MKSTREAM=key 不存在则建空流 | **NOAUTH**：先 `AUTH 123321`（我们的 Redis 有密码，教程没有）；**BUSYGROUP**=组已存在，不是错误 |
| `XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS key >` | 消费者 c1 读**新消息**（`>`），COUNT 最多几条，BLOCK 毫秒 | 读到消息 → 处理 → XACK |
| `XACK key 组名 id` | 记账本打勾（从 PEL 移除） | 处理完才打勾，读完不打 |
| `XPENDING key 组名` | 看记账本：多少条没 ACK、分给了谁 | 判断有没有漏单的体检命令 |
| `XREADGROUP ... STREAMS key 0` | 用 `0` 读**本消费者 PEL 里的待确认消息**（重取漏单） | 故障恢复循环的关键，见第四节 |
| `XINFO GROUPS/STREAM key` | 查组/流元信息 | 验证建组是否成功 |

**游标辨析（易混重点）**：
- `>` = 只读**从没分发过给任何消费者**的新消息（主循环用）；
- `0` = 读**本消费者名下已分发但没 ACK** 的消息（PEL，故障恢复用）；
- `$`（XGROUP CREATE 时）= 从"现在"开始算新，历史不看；`0`（XGROUP CREATE 时）= 从流的第一条开始看。

---

## 四、7.6 改造精讲（本篇核心）

### 4.1 改造点总览（对比 06 篇阻塞队列版，五处变化）

| # | 变化 | 原来（阻塞队列版） | 现在（Stream 版） |
|---|---|---|---|
| 1 | 入队时机 | 请求线程 `orderTasks.add(订单对象)` | **Lua 脚本内 `XADD`**（判断+入队原子） |
| 2 | 传的是什么 | 完整订单对象 | 一条消息（userId/voucherId/id 三字段） |
| 3 | 订单对象在哪造 | 请求线程造好 | **消费者线程由消息反序列化**（BeanUtil） |
| 4 | 队列 | JVM 内存 ArrayBlockingQueue | Redis Stream（跨进程、持久化） |
| 5 | 消费失败 | try/catch 里日志了事 | **留在 PEL → handlePendingList 重捞** |

### 4.2 改造点 1：XADD 为什么必须进 Lua

资格判断通过（incrby 预扣了库存、sadd 记了用户）**之后**、XADD 之前，如果进程崩溃——库存扣了、用户记了、消息没了 → 这个人"占了名额却永远等不到订单"。把 XADD 放进脚本，"判断 + 预扣 + 入队"整体原子，不存在这个缝。**又是 04 篇那句老话：两步分开写就有缝，塞进一条命令里才安全。**

### 4.3 改造点 2：orderId 为什么提前生成、传进脚本

消息要带上"这单的唯一身份"（id/userId/voucherId 三要素），消费者才能反序列化出订单。id 由请求线程用 RedisIdWorker 生成（`ARGV[3]` 传入）——**生成 id 也要调 Redis，如果在消费者里再生成，等于把 Redis 依赖拖进消费环节**；而且订单号要在返回给用户的"回执单"上，必须请求时就定下来。

### 4.4 消费者主循环逐行（当前真实代码）

```java
while (true) {
    try {
        // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                Consumer.from("g1", "c1"),                                   // 组/消费者名
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)), // 最多1条，阻塞2秒
                StreamOffset.create(queuename, ReadOffset.lastConsumed())    // lastConsumed = '>' 读新消息
        );
        if (list == null || list.isEmpty()) continue;       // 2秒没消息：continue 回去接着阻塞读
        MapRecord<String, Object, Object> record = list.get(0);
        // 消息字段 → 订单对象（hutool 按属性名自动转类型）
        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
        handleVoucherOrder(voucherOrder);                    // Redisson锁 → 事务写库（同06篇）
        // XACK stream.orders g1 <id>：处理完才打勾
        stringRedisTemplate.opsForStream().acknowledge(queuename, "g1", record.getId());
    } catch (Exception e) {
        log.error("处理订单异常", e);
        handlePendingList();                                 // ★ 异常 → 进故障恢复循环
    }
}
```

**Spring Data Redis 五件套对照表**（命令 ↔ API，默写锚点）：

| Redis 命令片段 | Java API |
|---|---|
| `GROUP g1 c1` | `Consumer.from("g1", "c1")` |
| `COUNT 1 BLOCK 2000` | `StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2))` |
| `STREAMS stream.orders` | `StreamOffset.create("stream.orders", ReadOffset.xxx)` |
| `>` | `ReadOffset.lastConsumed()` |
| `0`（PEL） | `ReadOffset.from("0")` |

### 4.5 改造点 3：handlePendingList——故障恢复闭环（★ 全篇最精妙处）

**场景**：XREADGROUP 拿到消息 1-2 → 处理时抛异常（DB 抖了）→ 没 ACK → 1-2 留在组 g1 的 PEL 里。主循环的 `>` 永远不会再读到它（它已经"分发过了"）→ **漏单**。

**恢复逻辑**：主循环一抛异常就切到 `handlePendingList`——把游标从 `>` 换成 `0`，专读**自己名下没 ACK 的消息**，逐条重试：

```java
private void handlePendingList() {
    while (true) {
        try {
            List<MapRecord<...>> list = ...read(Consumer.from("g1","c1"),
                    StreamReadOptions.empty().count(1),                 // 不阻塞：PENDING有就返回，没有立刻回
                    StreamOffset.create(queuename, ReadOffset.from("0"))); // ★ 0 = 读PEL
            if (list == null || list.isEmpty()) break;                  // PEL 清空了 → 回主循环
            ...同样的 解析 → handleVoucherOrder → acknowledge...
        } catch (Exception e) {
            log.error(...); Thread.sleep(20);                           // 还失败：歇20ms再试（避免疯狂打转）
        }
    }
}
```

**双循环状态机（手画题）**：

```
主循环：XREADGROUP(>) → 处理 → ACK
   │ 成功且无异常 → 继续读新消息（BLOCK 2s，空转挂起）
   │ 任何异常 ↓
恢复循环：XREADGROUP(0) 读PEL → 重试处理 → ACK
   │ PEL 空（break）→ 回主循环
   │ 又异常 → sleep 20ms → 继续读 PEL（不阻塞，快速重试）
```

> **温馨小贴士：为什么恢复循环不用 BLOCK**——PEL 里有没有漏单是"立刻可知"的（查记账本），没有就 break 回主循环继续等新消息；在这里阻塞 2 秒毫无意义还拖延恢复。主循环才需要 BLOCK（等新消息本来就要等）。
> **另一个坑**：handleVoucherOrder 失败时如果直接吞掉异常并 return（不抛出），ACK 照样执行 → 坏单被"打勾销账"，永远丢了。所以要让异常往主循环抛，走 PEL 恢复。

### 4.6 两个工程补强（自研改造，课程没讲）

1. **@PostConstruct 幂等建组**：教程让你手动 `xgroup create`，但 Redis 一旦 flush / 测试时删 key，启动后消费者疯狂抛 NOGROUP。启动时 `createGroup("stream.orders", ReadOffset.from("0"), "g1")`（MKSTREAM 自动建空流），已存在抛 BUSYGROUP 吞掉即可——**2026-11-22 实测**：删掉 stream.orders 后重启，g1 自动重建，E2E 正常。
2. **proxy 赋值顺序（竞态回归）**：XADD 挪进 Lua 后，"入队"发生在 Lua 里——proxy 赋值如果还写在 Lua 调用之后，消费者可能先醒来拿到 null。**必须在 execute 之前赋值**。又一次验证：改结构时，旧顺序假设会悄悄失效。

---

## 五、★ E2E 验证记录（2026-11-22 真实跑通）

**复位**（比 06 篇多了 stream 两步）：

```bash
redis-cli -a 123321 set seckill:stock:10 100
redis-cli -a 123321 del seckill:order:10 stream.orders    # 删流 = 顺带删组，测自动建组
mysql: update tb_seckill_voucher set stock=100 where voucher_id=10; 删测试订单
```

**验证结果**（新手机号 13686869698）：

| 检查项 | 命令 | 预期 | 实际 |
|---|---|---|---|
| 第1次秒杀 | POST seckill/10 | 立即返回订单号 | ✅ `663122510753038339` |
| 第2次秒杀 | 同上 | Lua 拦"不能重复下单" | ✅ |
| Redis 预扣 | `GET seckill:stock:10` | 99 | ✅ |
| 消息确实入队 | `XLEN stream.orders` | ≥1（ACK 不会减少！） | ✅ 1 |
| 消费+打勾闭环 | `XPENDING stream.orders g1` | 0（无漏单） | ✅ 0 |
| 自动建组 | 启动后 `XINFO GROUPS` | g1 存在（重启前流是删掉的） | ✅ |
| DB 落单/库存 | SQL | 1 条订单 / 99 | ✅ |

> ⚠️ 又一次"旧实例占 8081"（IDEA 里的实例是旧代码）——第三次了。**肌肉记忆：改完代码 → netstat 查 8081 → taskkill → 重启 → 再验证。**

---

## 六、踩坑记录（本章真实踩过）

| # | 坑 | 现象 | 处理 |
|---|---|---|---|
| 1 | redis-cli 没登录就执行 xgroup | `NOAUTH Authentication required`（我们的 Redis 有密码，教程没有） | `redis-cli -a 123321` 或先 `AUTH 123321` |
| 2 | 重复执行 xgroup create | `BUSYGROUP ... already exists`（不是错误，是提醒） | 先 `xgroup destroy` 再建，或忽略 |
| 3 | 手动建组 + 测试删 key | 重启后 NOGROUP 异常死循环刷日志 | @PostConstruct 幂等建组（4.6） |
| 4 | proxy 赋值在 Lua 之后 | 消费者可能拿到 null（竞态回归，4.6） | 赋值挪到 execute 之前 |
| 5 | ACK 后 XLEN 没变小，以为没打上勾 | 误解 ACK 语义 | ACK 只动 PEL 记账本，消息本体还躺在流里；`XPENDING` 看漏单、`XLEN` 看流长度，两个是不同维度的数 |
| 6 | handleVoucherOrder 吞异常 | 坏单被 ACK 销账，永久丢失 | 异常必须上抛走 PEL 恢复 |
| 7 | 旧 IDEA 实例占 8081 | 验证结果全是旧代码行为（第 3 次踩） | 见五节肌肉记忆 |

---

## 七、遗留问题与生产视角（简历加分点）

Stream 在 Redis 家族里最强，但离专业 MQ 还有距离，能主动说出局限=超出课程水平：

1. **可靠性受限于 Redis 本身**：主从切换时 PEL/流可能丢（同 04 篇锁丢失的根因，异步复制）；专业 MQ 有多副本共识机制。
2. **堆积默认无限**：不加 MAXLEN 流会无限吃内存；加 MAXLEN 丢历史消息又和"回溯"矛盾——专业 MQ 有成熟的保留策略。
3. **无死信队列/重试策略**：PEL 里的坏单要自己写恢复循环（我们写了），重试次数限制、死信转存都没有。
4. **消费幂等**：消费者可能重复处理同一消息（PEL 重捞），我们的兜底是 count 校验 + Redisson 锁——幂等要业务层保证，MQ 不帮你去重。

> 一句话定位（面试用）："课程里用 Stream 实现，理解了生产者-消费者、ACK、PEL 故障恢复的完整机制；知道它在可靠性和堆积治理上不及 RabbitMQ/Kafka，生产选型会权衡。"——这句话把"跟课"提升到"有选型判断"。

---

## 八、★ 独立复写训练路线（治"跟着敲会、自己写不出"）

**诊断**：你现在的状态是"认得"不是"记得"——代码摆在眼前每行都对得上号，闭卷下不了笔。这不是没学会，是缺一次**主动提取**。练法如下（总共 1~1.5 小时，建议这周内做完）：

### 第 1 轮：口述骨架（20 分钟，不动键盘）

不看任何材料，白板/白纸画出整个链路，要求画出这些块：

```
①请求线程：拿userId → 生成orderId → 拿proxy → 执行Lua(3参: voucherId/userId/orderId)
②Lua内部：判库存(1) → 判一人一单(2) → 预扣incrby → sadd记用户 → XADD入队 → return 0
③返回：r!=0 按码返回文案；r=0 返回 orderId
④消费者主循环：XREADGROUP(g1,c1,COUNT1,BLOCK2s,>) → 空 continue
   → fillBeanWithMap 反序列化 → handleVoucherOrder(Redisson锁+事务) → XACK
   → 异常 → handlePendingList
⑤恢复循环：XREADGROUP(0) 读PEL → 同样处理+ACK → PEL空 break → 异常 sleep(20) 重试
```

讲不出来的块就是没懂，回去翻对应小节（④→4.4，⑤→4.5），**只翻卡住的那块**。

### 第 2 轮：默写骨架（30 分钟，开 IDEA 不开视频）

新建 `VoucherOrderServiceImpl2.java`（纯练手，写完删），**只许看第 1 轮你画的图**，把代码默出来。卡壳超过 1 分钟 → 在图上把那个块标记出来，跳过继续（别死磕）。写完编译，错的地方对照原文件标红。

**高频卡壳点预判**（默写前先背这三个锚点）：
1. Lua 三参顺序 `voucherId, userId, orderId`，execute 参数全 `toString()`；
2. 五件套 API：`Consumer.from` / `StreamReadOptions.empty().count().block()` / `StreamOffset.create` / `ReadOffset.lastConsumed()`(主) vs `ReadOffset.from("0")`(PEL)；
3. 双循环唯一区别就一个字符：游标 `>` vs `0`——记住了这个，两个循环是一段代码的复用。

### 第 3 轮：变量重写（20 分钟）

换条件重写一遍（防"背答案"）：消费组改 `g2`、消费者名改 `order-reader`、BLOCK 改 5 秒、消息字段加一个 `payTime`（订单对象加对应字段）。能换着写 = 真理解了。

### 第 4 轮：口述面试题（对照第十节）

对着"必背五题"逐题口述，答不上来的标记回炉。

---

## 九、八股结合点

1. **生产者-消费者模式**：06 篇 BlockingQueue（JVM 内）→ 本篇 Stream（跨进程）——同一个模式的两种载体；MQ 的解耦/削峰/异步三大好处结合秒杀各举一例。
2. **消息可靠性三板斧**：持久化（落盘）+ ACK 确认 + 重试/死信——Stream 有前两个半，专业 MQ 全有；对照 List 版（零 ACK）和 PubSub（零持久化）讲很立体。
3. **消费幂等**：at-least-once 投递必然可能重复消费 → 业务层幂等（唯一约束/防重锁/状态机）——本项目 count 兜底 + Redisson 锁就是幂等手段。
4. **Redis 数据结构全景**：String(缓存/锁值/库存)、Set(一人一单/抽奖)、Hash(购物车/可重入锁)、ZSet(排行榜)、List(简单队列)、Stream(消息队列)——黑马课程学完正好凑齐全家福，面试背结构场景对应表。
5. **阻塞的本质**：XREAD BLOCK / BRPOP / take() 都是"线程挂起等待+数据到达唤醒"，一原理三处用。

---

## 十、面试题库

### 🔥 必背五题

1. **为什么最终选 Stream 消息队列，而不是阻塞队列/List/PubSub？** 阻塞队列三宗罪（内存有界/宕机丢/跨不了 JVM）；List 取走即丢无 ACK；PubSub 不持久化离线丢。Stream：持久化在 Redis + 消费者组竞争消费 + ACK/PEL 故障恢复，三宗罪全解。
2. **Stream 消费者组怎么工作？> 和 0 的区别？** 组内消费者竞争消费（一条消息只给组内一人，多机负载均衡）；`>` 读从未分发的新消息（主循环，可 BLOCK）；`0` 读本消费者名下已分发未 ACK 的 PEL 消息（故障恢复循环）。建组时 `0`=从头消费历史、`$`=只消费新建后的、MKSTREAM=流不存在则建空流。
3. **消息处理失败怎么办？（PEL 恢复机制）** XREADGROUP 分发后消息进组 PEL，处理完才 XACK。主循环异常 → 切恢复循环：游标改 0 逐条重捞 PEL → 重试处理 → 补 ACK → PEL 空回主循环。注意：业务异常不能吞（吞了会照常 ACK，坏单销账）。
4. **为什么把 XADD 放进 Lua 脚本？** 资格判断（预扣库存+记用户）和消息入队是两步，分开写有缝：判过但没来得及入队时崩溃，名额被占而订单永远不来。放脚本里整体原子——和分布式锁"判断+删除"进 Lua 是同一个思想。
5. **这套方案还有什么问题？生产怎么选型？** Stream 受限于单机 Redis（主从切换可能丢消息/PEL）、堆积治理弱（MAXLEN 与回溯矛盾）、无死信与重试策略、消费幂等靠业务层（我们用 count+Redisson 兜底）。生产按可靠性和吞吐要求在 RabbitMQ/RocketMQ/Kafka 里选。

### ⚡ 追问链

- ACK →"XACK 后消息还在流里，怎么清理？"（历史消息要 XDEL / MAXLEN 截断，课程没做——主动说出这点加分）
- PEL →"消费者宕机后 PEL 里的消息怎么办？"（其他消费者可用 XCLAIM/XAUTOCLAIM 认领——课程不深入，知道有这机制）
- 幂等 →"消息重复消费会怎样？"（count 兜底拦住；严格场景用唯一索引兜底）
- 顺序 →"消息有序吗？"（单流内按 id 有序；组内分发顺序消费保证）
- 对比 →"Redis MQ vs RabbitMQ 怎么选？"（轻量已有 Redis→Stream 够用；可靠性要求高/复杂路由→专业 MQ）

### 🎨 白板手画清单

- Stream 结构 + PEL 记账本心象图（一节的心象图）
- 双循环状态机（主循环 > ↔ 恢复循环 0，异常切换 + break 回归）
- 三方案评分表（List/PubSub/Stream × 三宗罪/ACK/回溯/广播）
- 7.6 改造五处变化对照（4.1 表）

---

## 十一、自测清单（口述过关标准）

- [ ] 用"出餐台搬出 JVM"一句话讲清 MQ 解决了什么
- [ ] 背出 List/PubSub/Stream 各自的致命伤（丢消息的三种方式）
- [ ] 区分 `>`、`0`、`$` 三个游标（两处在 XREADGROUP，一处在 XGROUP CREATE）
- [ ] 口述双循环状态机 + "为什么恢复循环不用 BLOCK"
- [ ] 说清 XADD 进 Lua 的原因（又是"两步有缝"）
- [ ] 说清 ACK 时机与语义（处理完才打勾；XLEN 不变）
- [ ] 口述 NOAUTH/BUSYGROUP/NOGROUP 三兄弟的处理
- [ ] 完成第八节四轮训练（口述→默写→变量重写→面试题）
- [ ] 说出 Stream 四条局限 + 选型一句话（第七节）

---

## 十二、参考资料

- 课程：黑马点评实战篇 07 Redis 消息队列（ch7.1~7.6）
- 本仓库实战笔记：`Redis\02-实战篇\实战笔记\Redis实战篇.md` 第 7 章（2504 行起）
- 代码：`resources/seckill.lua`（XADD 入队版）、`service/impl/VoucherOrderServiceImpl.java`（双循环消费者）、`service/IVoucherOrderService.java`
- 关联笔记：`06-秒杀优化-异步秒杀上.md`（概念垫底 〇.5 / 阻塞队列版 / 三宗罪）、`05-Redisson.md`（handleVoucherOrder 的锁）、`04-分布式锁.md`（Lua 原子性总纲）
- 测试数据：券 id=10；69696/69697/69698 均已用资格（user_id 1019/1020/新号）；复位命令见第五节
