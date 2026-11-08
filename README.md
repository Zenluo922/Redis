# Redis 学习指南（入门速成 + 黑马点评实战篇导航）

> 依据黑马《Redis实战课》全量资料（本目录 `01-入门篇/` + `02-实战篇/`，讲义均为老师注释版 Markdown）
> 制定时间：2026-11-07 ｜ 目标：**11-8 一天速成入门篇**，11-9 进黑马点评——**02-实战篇就是黑马点评课程本体，跟着项目走，不在 11-8 速成**
>
> **核心策略：11-8 只做"会装、会连、会用"——五大类型命令 + SpringDataRedis 三件套，把深度全部留给项目期。**
> Redis 的面试价值不在入门篇（那点命令背不背无所谓），而在实战篇的**缓存三件套 / 秒杀 / 分布式锁**——那是跟着黑马点评做出来的，不是一天速成出来的。

---

## 〇、目录导航（每个文件是什么）

| 路径 | 是什么 | 用法 |
|------|--------|------|
| `01-入门篇/讲义/Redis注释版/Redis.md` | **入门篇全量笔记**（1569 行：NoSQL/安装/客户端/五大类型命令/Jedis/SpringDataRedis） | **主要看这份**；`01.快速入门.md` 是它的子集，不用重复看 |
| `01-入门篇/代码/redis-demo.zip` | 入门篇跟做代码（Boot 2.5.7 + JDK8） | 解压对照用；跟做时**用 Boot 3.3.x + JDK17 自己重建**，只抄结构不抄版本 |
| `01-入门篇/资料/Redis安装说明.md` | Linux（CentOS）安装 Redis 6.2.6 全流程 | 文档过一遍即可，见避坑① |
| `01-入门篇/资料/resp-2022.2.zip` | RESP 图形客户端（Windows 免安装） | 装好后整个课程都用它看数据 |
| `02-实战篇/讲义/Redis实战篇.md` | **黑马点评 12 模块完整笔记**（3814 行） | 项目期逐模块配合网课用，11-9 起 |
| `02-实战篇/资料/hm-dianping.zip` + `hmdp.sql` | 黑马点评后端原始工程 + 数据库脚本 | 11-9 项目启动第一天导入 |
| `02-实战篇/资料/nginx-1.18.0.zip` | 点评前端（nginx 托管） | 同上 |
| `02-实战篇/代码/完整实现版代码.zip` | **全部做完的** hm-dianping（含 .git） | 卡住时对照，**别照抄**——抄完的项目简历上不是你的 |
| `02-实战篇/资料/秒杀抢购.jmx` | JMeter 压测脚本 | 到秒杀章节（验证超卖）再装 JMeter |
| `Redis课程大纲-V1.0.xmind` | 课程总大纲 | xmind 格式，想看全景时打开 |

---

## 一、入门篇速成（11-8，一天）分块重点

### 1. 初识 Redis 🔥（1 小时，快过）
- NoSQL vs SQL 四个差异（结构化/关联/查询方式/事务）**能口述一句话版本**即可，不背表格
- 认识 Redis：内存键值数据库、单线程、五大应用场景（缓存/排行榜/计数/分布式锁/存储）——**面试"为什么用 Redis"的素材底子**
- 安装：✅ **已完成（11-7 已在你的 CentOS 7.6 虚拟机装好 Redis 6.2.6，见避坑①）**，你只需开虚拟机 + RESP 连上即达标
- redis-cli + RESP 两个客户端都会用：命令行是面试白板场景，图形客户端是你日常调试场景

### 2. 五大基本类型 + 通用命令 🔥🔥（半天，动手敲）
- **通用命令**：KEYS / DEL / EXISTS / EXPIRE / TTL——EXPIRE+TTL 是缓存和登录态的地基，必须熟练
- **String**：SET/GET/SETNX/INCR/DECR——SETNX（不存在才设置）是分布式锁的原材料，INCR 是计数器，两个都留印象
- **Hash**：HSET/HGET/HGETALL——**存对象的正解**（对比 String 存整个 JSON），黑马点评用户信息就用它
- **List**：LPUSH/RPUSH/LPOP/RPOP/BRPOP——双向链表，记住可当简易消息队列（实战篇 ch7 会展开）
- **Set**：SADD/SREM/SISMEMBER/SCARD——无重复+交并集，共同关注（实战篇 ch9）的原材料
- **SortedSet**：ZADD/ZRANGE/ZSCORE/ZRANK——**带分数排序，排行榜唯一正解**，点赞排行榜（实战篇 ch8）用它
- **Key 层级结构必会**：`业务名:数据类型:id`（如 `heima:user:1`）——命名规范是工程素养的体现，面试可提
- ✅ 检验：RESP 里五大类型各手敲一组命令建出真实数据；每种类型**说出一个应用场景**

### 3. Java 客户端 🔥🔥🔥（半天，黑马点评直接用）
- **Jedis 快过（30 分钟）**：知道它是老牌客户端、需要手动建连接池（依赖 commons-pool2）即可；Boot 默认是 **Lettuce**，别在 Jedis 代码上耗时间
- **SpringDataRedis 是正主**（对照 SpringBoot ch4 看自动配置）：
  - 引 `spring-boot-starter-data-redis` → yml 配连接 → 直接注入 `RedisTemplate`，零配置
  - **RedisTemplate 默认 JDK 序列化的坑必踩一次**：存个对象进 RESP 看乱码 → 理解为什么（序列化器默认 JdkSerialization）→ 两种解法：
    - 自定义序列化：key 用 String 序列化器，value 用 JSON（GenericJackson2JsonRedisSerializer）
    - **StringRedisTemplate**（实战主流）：key/value 都是 String，对象手动 JSON 互转——黑马点评全程用它
  - 序列化方案对比能口述：JDK 序列化可读性差+体积大 / JSON 可读但占内存 / String+手动转换最可控
- ✅ 检验：**用 Boot 3.3.x + JDK17 新建 redis-demo**，yml 连本机 Redis，String 和对象（JSON）读写各跑通一遍

---

## 二、实战篇 = 黑马点评本体（11-9 起，随项目学，不在 11-8）

课程 12 个模块与你的项目实战计划完全对应，优先级如下：

| 优先级 | 模块 | 对应实战篇章 | 备注 |
|--------|------|--------------|------|
| 🔥🔥🔥 面试重灾区 | 短信登录（Redis 代替 session + 登录拦截刷新） | ch1 | 项目第一个功能，token 存 Redis 的标准姿势 |
| 🔥🔥🔥 面试重灾区 | 商户查询缓存（**穿透/雪崩/击穿** + 互斥锁/逻辑过期 + 双写一致） | ch2 | **全课程面试价值最高**，缓存工具类封装直接进简历 |
| 🔥🔥🔥 面试重灾区 | 优惠券秒杀（全局 ID/超卖/乐观锁/一人一单）+ 分布式锁 + Redisson + 异步秒杀 + 消息队列 | ch3~7 | CLAUDE.md 预想追问"秒杀怎么防超卖"的答案全在这条线 |
| 🔥 跟做即可 | 达人探店/好友关注/附近商户(GEO)/签到(BitMap)/UV(HyperLogLog) | ch8~12 | 特殊类型应用场景，跟做+留一句话印象（面试"还知道哪些类型"的加分答案） |

> 项目期的详细排期、AI 模块改造点见 CLAUDE.md「项目实战计划」，不在本 README 重复。

---

## 三、面试高频题自测清单（11-8 晚自测入门部分，其余项目期边做边收）

| # | 面试题 | 出处 | 掌握标准 |
|---|--------|------|----------|
| 1 | Redis 五大基本类型及应用场景？ | 入门 ch2 | 每类型一句场景（缓存对象/排行榜/共同关注/去重/消息队列） |
| 2 | Redis 为什么快？ | 入门 ch1 | 内存 + 单线程避免锁竞争 + IO 多路复用，三句讲清 |
| 3 | RedisTemplate 存对象为什么乱码？怎么解决？ | 入门 ch3 | 默认 JDK 序列化 → 自定义 JSON 序列化器 or StringRedisTemplate，两种都说 |
| 4 | Jedis / Lettuce / Redisson 区别？ | 入门 ch3 | Jedis 老牌直连 / Lettuce 线程安全 Boot 默认 / Redisson 分布式锁工具集 |
| 5 | 缓存穿透/雪崩/击穿分别是什么？怎么解？ | 实战 ch2 | 三者定义不混 + 各配方案（空值缓存/随机 TTL/互斥锁或逻辑过期）——重灾区 |
| 6 | 缓存与数据库双写一致性怎么保证？ | 实战 ch2 | 先更新数据库再删除缓存（Cache Aside），能说为什么不是先删缓存 |
| 7 | 互斥锁和逻辑过期各解决什么？取舍？ | 实战 ch2 | 一致性 vs 可用性；逻辑过期不阻塞但有短暂脏数据 |
| 8 | Redis 分布式锁怎么实现？有哪些坑？ | 实战 ch4 | setnx+过期时间 → 误删问题（锁标识）→ 原子性（Lua）演进链白板能画 |
| 9 | Redisson 看门狗（WatchDog）是什么？ | 实战 ch5 | 不指定超时则自动续期，默认 30s、每 1/3 续一次 |
| 10 | 秒杀怎么防超卖？一人一单怎么做？ | 实战 ch3 | 乐观锁（库存>0）+ 悲观锁/分布式锁兜住一人一单 + 最终 Lua 异步判断 |
| 11 | 为什么登录要用 Redis 不用 session？ | 实战 ch1 | 集群 session 共享问题 + Redis 可设 TTL 天然适合 token |
| 12 | Redis 消息队列三种方案对比？ | 实战 ch7 | List（无 ACK）/ PubSub（不持久）/ Stream（消费者组+ACK），一句话各自短板 |

---

## 四、时间规划（11-8 一天）

| 时段 | 内容 | 产出检验 |
|------|------|----------|
| 上午 | 初识 Redis + 开虚拟机连 RESP → 五大类型命令逐个敲（对照 Redis.md ch4） | RESP 五大类型各有一组亲手建的数据；每类型说出一个场景 |
| 下午前半 | Key 层级命名 → SpringDataRedis：Boot 3 新建 redis-demo，RedisTemplate + 乱码坑 + 两种序列化方案 | redis-demo 跑通：String 读写 + 对象 JSON 读写（RESP 里肉眼可读） |
| 下午后半 | 面试自测清单 #1~4 过关 → 八股顺路收（当天口述过关，见八股题单） | 自测前 4 题不看答案能讲 |

> **算法打卡照常每天 1 题，不断。** 11-9 直接启动黑马点评（导入 hm-dianping.sql + 前端 nginx，按 CLAUDE.md 项目计划走）。

---

## 五、学习检验标准（11-8 收工时你应该能做到）

- [ ] 虚拟机 Redis 能自己管理（`systemctl start/stop/status redis`），RESP 连接成功，redis-cli 敲过通用命令
- [ ] 五大类型命令不看笔记各敲一组，每种类型说出一个真实应用场景
- [ ] 手写 Boot 3 + SpringDataRedis 全流程：starter → yml → 注入 → 读写
- [ ] 踩过并讲清 RedisTemplate JDK 序列化坑，StringRedisTemplate 跑通对象存取
- [ ] 能一句话说清"11-8 学的东西在黑马点评里分别用在哪个功能"（INCR→全局ID雏形 / SETNX→锁 / Hash→用户缓存 / SortedSet→排行榜 / EXPIRE→token）

---

## 六、避坑提醒

1. **Redis 已装在你的 VMware 虚拟机里（11-7 已代装完成）**——CentOS 7.6（`D:\Develop\Centos7\`），静态 IP `192.168.197.131`（重启不变），Redis 6.2.6、密码 `123321`（与课程讲义一致，仅 IP 不同：讲义里是 192.168.150.101）**；库数量 16（教程是 databases 1，为方便练多库；黑马点评只用 0 号库不受影响）**；RESP 客户端已装 `D:\Develop\RESP`（桌面有快捷方式）。目录布局与教程对齐：源码在 `/usr/local/src/redis-6.2.6`，可执行文件 `/usr/local/bin`，**生效配置在 `/etc/redis/redis.conf`（教程里老师改源码目录的 redis.conf 时，对应改这份）**，数据日志 `/usr/local/redis/`。管理命令 `systemctl start/stop/status redis`，**已设开机自启**，防火墙已放行 6379。Windows 本机不用装 Redis；**先在 VMware 开机这台虚拟机再连**（VM 关了 Redis 自然连不上）。Java/Boot 的 yml 写 `host: 192.168.197.131` + `password: 123321`
2. **Boot 3 下 Redis 配置前缀是 `spring.data.redis`，不是课程的 `spring.redis`**——写错不报错，但连的是默认 localhost 非密码库，排查半天；这是 2.x 跟做改 3.x 的第一大坑
3. **课程环境 JDK8 + Boot 2.5.7，你统一用 JDK17 + Boot 3.3.x 重建**——javax→jakarta、Geo DashBoard 等差异到实战篇遇到再查；不要为了跟课程装回 JDK8
4. **RedisTemplate 直接存对象 = 乱码不是 bug**——默认 JdkSerializationRedisSerializer；先故意踩一次看乱码，再配序列化，比直接背结论记得牢
5. **Jedis 连接池测试报 ClassNotFound 先查 commons-pool2**——课程 pom 里有，自己重建容易漏
6. **RESP 客户端资料里现成的**（`resp-2022.2.zip` 免安装）；另有一个 Another Redis Desktop Manager 更现代，二选一即可，别在选客户端上花时间
7. **实战篇资料 11-9 再动**——hmdp.sql 到时导入本地 MySQL 8（注意它原是 5.7 的库，导入有报错随时问）；JMeter 等到秒杀压测章节再装
8. **完整实现版代码是答案册不是教材**——某一步卡超过 20 分钟再打开对照，对完关掉自己重写
9. 入门篇 Linux 安装/开机自启那一节（1.3）**读文档即可不用实操**（你本机 Windows 方案不同），把时间省给五大类型命令和 SpringDataRedis
