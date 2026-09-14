# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目状态（先读这一段）

这是《黑马点评》实战课程的**完整实现**，已按讲义 12 个章节逐章补齐、逐章提交（`git log` 里一章一个 commit）。原始骨架里的 `// TODO 功能未完成` 桩已基本消除。各章落地位置：
- **第1章 短信登录**：`UserController` 的 `code`/`login`/`me`、`UserServiceImpl`（Redis token 登录）、`utils/LoginInterceptor`、`utils/RefreshTokenInterceptor`、`config/MvcConfig`
- **第2章 商户查询缓存**：`utils/CacheClient`（封装 `queryWithPassThrough` / `queryWithMutex` / `queryWithLogicalExpire` / `setWithLogicalExpire`）、`ShopServiceImpl.queryById` 与 `update`（先改库再删缓存）、`ShopController` 改为走 service
- **第3章 优惠卷秒杀**：`utils/RedisIdWorker`（时间戳左移 32 位 + Redis 日自增序列号）、`VoucherServiceImpl.addSeckillVoucher` 额外把库存写入 `SECKILL_STOCK_KEY`、`VoucherOrderServiceImpl.seckillVoucher`（校验时间与库存 → `synchronized(userId.intern())` → 代理调用 `createVoucherOrder`）、`HmDianPingApplication` 加了 `@EnableAspectJAutoProxy(exposeProxy = true)`
- **第4章 分布式锁**：`utils/ILock` + `utils/SimpleRedisLock`（`setIfAbsent` 加锁、value 存 `UUID-线程id` 标示、`resources/unlock.lua` 保证「比标示 + 删锁」原子性）、`VoucherOrderServiceImpl` 的 `synchronized` 换成 `SimpleRedisLock`
- **第5章 Redisson**：`pom.xml` 新增 `org.redisson:redisson:3.13.6`、`config/RedissonConfig`、`VoucherOrderServiceImpl` 改用 `RLock`（`SimpleRedisLock` 保留在 `utils` 但已无人使用）
- **第6章 秒杀优化**：`resources/seckill.lua`（原子完成「查库存 + 一人一单 + 扣库存 + 记录用户」）、`VoucherOrderServiceImpl` 改为 `@PostConstruct` 起单线程 + `ArrayBlockingQueue` 异步落库；`IVoucherOrderService` 收窄为只剩 `seckillVoucher`，`createVoucherOrder` 变成私有方法（**从线程池线程调用，脱离 Spring 事务上下文，所以它上面没有也不该有 `@Transactional`**）
- **第7章 Redis消息队列**：`seckill.lua` 末尾追加 `XADD stream.orders`，`VoucherOrderServiceImpl` 的阻塞队列换成 Redis Stream 消费者组（`stream.orders` / `g1` / `c1`），新增 `handlePendingList()` 补偿未 ACK 的消息
- **第8章 达人探店**：`BlogServiceImpl` 承载原 `BlogController` 里的大部分逻辑（`saveBlog` / `queryHotBlog` / `queryBlogById` / `likeBlog` / `queryBlogLikes` / `isBlogLiked` / `queryBlogUser`），控制器改为瘦转发；点赞用 `BLOG_LIKED_KEY` 的 **ZSet**（score = 点赞时间戳）实现去重 + 排行榜 `zrange 0 4`
- **第9章 好友关注**：`FollowServiceImpl`（关注/取关同步维护 `follows:{userId}` Set、`followCommons` 用 `SINTER` 求共同关注）、`BlogServiceImpl.saveBlog` 写扩散推送笔记到粉丝 `FEED_KEY` ZSet、`queryBlogOfFollow` 滚动分页（`reverseRangeByScoreWithScores` + 返回 `ScrollResult`）；新增 `/blog/of/follow`、`/blog/of/user`、`/user/{id}`
- **第10章 附近商户**：`ShopServiceImpl.queryShopByType` 支持按 `x`/`y` 坐标走 Redis GEO（`GEOSEARCH` + `includeDistance` + 内存分页），无坐标时回退到数据库分页；测试类新增 `loadShopData()` 按 `typeId` 分组把店铺灌进 `SHOP_GEO_KEY`
- **第11章 用户签到**：`UserServiceImpl.sign()` 用 `SETBIT` 按 `sign:{userId}:yyyyMM` 逐日置位、`signCount()` 用 `BITFIELD GET u{dayOfMonth} 0` 取出本月位图后靠 `& 1` + 右移数连续 1，新增 `/user/sign`、`/user/sign/count`

- **第12章 UV统计**：测试类 `testHyperLogLog()` 向 HyperLogLog 灌 100 万条数据验证误差

**Stream 消费组必须先手工创建**：`VoucherOrderServiceImpl` 只做 `XREADGROUP`，不做 `XGROUP CREATE`。首次跑之前需要在 redis-cli 执行 `XGROUP CREATE stream.orders g1 0 MKSTREAM`，否则消费线程会一直报 `NOGROUP No such key`。

**唯一剩下的桩**：`UserController.logout` —— 讲义从头到尾没有实现它，参考实现也保留为 TODO。`BlogCommentsController` / `BlogCommentsServiceImpl` 是空壳（讲义未涉及评论功能）。`utils/SimpleRedisLock` 与 `utils/ILock` 在第5章被 Redisson 取代后已无人调用，作为第4章的产物保留。

**`AopContext.currentProxy()` 的前提**：项目里所有靠「同类内部调用走代理」来让 `@Transactional` 生效的写法，都依赖启动类上的 `@EnableAspectJAutoProxy(exposeProxy = true)`。删掉这个注解会让 `currentProxy()` 直接抛 `IllegalStateException`。

**登录链路（第1章已补齐）**：`UserHolder` 由拦截器写入。`RefreshTokenInterceptor` 注册在 `order(0)` 拦截 `/**`，从请求头 `authorization` 取 token → 查 Redis hash → `UserHolder.saveUser` → 刷新 TTL，并在 `afterCompletion` 调 `removeUser()`；`LoginInterceptor` 注册在 `order(1)`，只判断 ThreadLocal 有无用户，无则返回 401。**新增需要登录态的接口，务必确认它没有被 `MvcConfig.excludePathPatterns` 放行，否则拿不到用户。**

## 常用命令

```bash
mvn clean package                 # 构建
mvn spring-boot:run               # 启动（端口 8081）
mvn test                          # 全部测试
mvn test -Dtest=HmDianPingApplicationTests        # 单个测试类
mvn test -Dtest=HmDianPingApplicationTests#方法名   # 单个测试方法
```

## 运行前置依赖（硬编码在配置里，非本机默认值）

`src/main/resources/application.yaml`：

- MySQL：`127.0.0.1:3306/hmdp`，账号 `root` / 密码 `123`，驱动 `com.mysql.jdbc.Driver`
- Redis：`192.168.150.101:6379`，密码 `123321` —— 课程环境的远程虚拟机 IP，本地跑必须改成 `127.0.0.1`
- 服务端口 `8081`；`com.hmdp` 包日志级别为 `debug`

另有一处硬编码路径需要留意：`SystemConstants.IMAGE_UPLOAD_DIR` 指向 `D:\lesson\nginx-1.18.0\html\hmdp\imgs\`，`UploadController` 直接依赖它，路径不存在会导致上传失败。前端页面由该 nginx 静态目录托管，**前端代码不在本仓库内**。

**`RedissonConfig` 的地址是写死的**（`redis://192.168.150.101:6379` + 密码 `123321`，与讲义一致，不读 `application.yaml`）。注意 `Redisson.create(config)` 在**容器启动时就会建连**，Redis 不可达会直接导致应用启动失败 —— 这与 `StringRedisTemplate` 的懒连接行为不同。换环境时这是第一个要改的地方。

## 技术栈

Spring Boot 2.3.12 / Java 8 / Maven 单模块。MyBatis-Plus 3.4.3、MySQL Connector 5.1.47、spring-boot-starter-data-redis（Lettuce + commons-pool2）、Hutool 5.7.17、Redisson 3.13.6、Lombok。

**`spring-data-redis` 被刻意升级过**（第10章引入）：`spring-boot-starter-data-redis` 里排除了自带的 `spring-data-redis` 和 `lettuce-core`，改用 `spring-data-redis:2.6.2` + `lettuce-core:6.1.6.RELEASE`。原因是 2.3.x 自带的 2.3.9 不支持 Redis 6.2 的 `GEOSEARCH`（`org.springframework.data.redis.domain.geo.GeoReference` 这个类在 2.3.9 里根本不存在）。**改 pom 时不要"顺手清理"这两个版本号**，否则 `ShopServiceImpl.queryShopByType` 直接编译不过。

## 代码结构约定

分层是 controller → service（`IService<T>` / `ServiceImpl<Mapper, Entity>`）→ mapper（继承 `BaseMapper<T>`），实体在 `com.hmdp.entity`。

几点容易踩的：

- **Mapper 扫描**靠启动类上的 `@MapperScan("com.hmdp.mapper")`，不在启动类同包下的接口必须有它才会注册。
- **分页必须走 MP 拦截器**。`MybatisConfig` 注册了 `PaginationInnerInterceptor(MYSQL)`，`Page<>` 查询才生效；改动分页相关代码时不要绕过这个 bean。
- **实体别名**由 `mybatis-plus.type-aliases-package: com.hmdp.entity` 提供，XML 里可直接写短名。
- **Controller 常直接用 MP 的链式 API**（`service.query().eq(...).page(...)`、`service.update().setSql(...)`）而不是自定义 service 方法，这是本仓库的既有风格，新增简单查询可以照此写；复杂事务逻辑才落到 service。
- **所有 Controller 统一返回 `com.hmdp.dto.Result`**（`Result.ok(...)` / `Result.fail(msg)`）。`WebExceptionAdvice` 会兜住 `RuntimeException` 并转成 `Result.fail("服务器异常")`，所以业务里**直接抛 `RuntimeException` 就是标准错误路径**，不要自己 try-catch 成其他响应格式。
- `spring.jackson.default-property-inclusion: non_null` —— 返回 JSON 时会丢掉 null 字段，前端依赖这一点，不要改成 always。

## Redis Key 与缓存设计

Key 前缀与 TTL 统一定义在 `RedisConstants`，新增 key 请加到这里而不是散落字符串字面量：

| 常量 | 值 | 用途 |
|---|---|---|
| `LOGIN_CODE_KEY` / `LOGIN_CODE_TTL` | `login:code:` / 2 | 短信验证码（**分钟**） |
| `LOGIN_USER_KEY` / `LOGIN_USER_TTL` | `login:token:` / 36000 | 登录 token（**分钟**，即 25 天） |
| `CACHE_SHOP_KEY` / `CACHE_SHOP_TTL` | `cache:shop:` / 30 | 商铺缓存 |
| `CACHE_NULL_TTL` | 2 | 缓存空值防穿透 |
| `LOCK_SHOP_KEY` / `LOCK_SHOP_TTL` | `lock:shop:` / 10 | 商铺缓存互斥锁（`CacheClient` 内部用） |
| `SECKILL_STOCK_KEY` | `seckill:stock:` | 秒杀库存（第6章起由 `seckill.lua` 直接拼 key 使用） |
| `BLOG_LIKED_KEY` | `blog:liked:` | 点赞 **ZSet**（member=userId，score=点赞时间戳） |
| `FEED_KEY` | `feed:` | 关注 Feed 收件箱 |
| `SHOP_GEO_KEY` | `shop:geo:` | 店铺 GEO |
| `USER_SIGN_KEY` | `sign:` | 签到 BitMap |

注意 `seckill:order:{voucherId}` 这个「已下单用户」Set 的 key **只硬编码在 `seckill.lua` 里**，`RedisConstants` 中没有对应常量。

同理，第9章的关注关系 Set 用的 `follows:{userId}` 也是**硬编码在 `FollowServiceImpl` 里的字符串**，没有进 `RedisConstants`。`FEED_KEY`（`feed:`）则有常量。

`utils/RedisData`（`expireTime` + `data`）是为**逻辑过期**缓存方案预留的载体，目前无人使用。

## 数据库表

`tb_user`、`tb_user_info`、`tb_shop`、`tb_shop_type`、`tb_voucher`、`tb_seckill_voucher`、`tb_voucher_order`、`tb_blog`、`tb_blog_comments`、`tb_follow`。秒杀券是 `tb_voucher` + `tb_seckill_voucher` 两张表（后者存 stock/begin_time/end_time），`VoucherMapper.xml` 已写好在 `queryVoucherOfShop` 里做左连接。

数据库初始化脚本在 `src/main/resources/db/hmdp.sql`（Navicat 导出的完整建表语句 + 初始数据）。
