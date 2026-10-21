# 黑马点评 hm-dianping

基于 **Spring Boot + Redis** 的本地生活点评类后端，覆盖缓存、分布式锁、秒杀、消息队列、社交关系、地理位置、签到统计等典型高并发场景，并在此基础上扩展了**接口限流**与 **AI 智能客服**。

## 技术栈

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Spring Boot | 2.3.12.RELEASE | 基座 |
| Java | 8 | |
| MyBatis-Plus | 3.4.3 | ORM |
| MySQL | 8.x | 主存储 |
| Redis | 6.2+ | 缓存 / 锁 / 消息队列 / 位图 / GEO |
| Redisson | 3.13.6 | 分布式锁 |
| spring-data-redis | 2.6.2 | 需升级后才支持 `GEOSEARCH` |
| Hutool | 5.7.17 | 工具库 |
| Lombok | 随 Boot 版本 | 简化样板代码 |

## 功能一览

### 课程核心（12 章）

| # | 功能 | 关键技术 |
| --- | --- | --- |
| 1 | 短信登录 | Redis Hash 存登录态 + 双拦截器 + ThreadLocal |
| 2 | 商户查询缓存 | 缓存穿透 / 击穿 / 雪崩的完整应对 |
| 3 | 优惠券秒杀 | 全局唯一 ID（位运算）、乐观锁防超卖、一人一单 |
| 4 | 分布式锁 | `SETNX` + Lua 保证解锁原子性 |
| 5 | Redisson | 可重入锁、看门狗续期、MultiLock |
| 6 | 秒杀优化 | Lua 脚本原子判资格 + 异步下单 |
| 7 | Redis 消息队列 | Stream 消费者组 + pending-list 补偿 |
| 8 | 达人探店 | 点赞去重与排行榜（ZSet） |
| 9 | 好友关注 | Set 求共同关注 + Feed 推模式 + 滚动分页 |
| 10 | 附近商户 | GEO / `GEOSEARCH` 按距离排序 |
| 11 | 用户签到 | BitMap + `BITFIELD` 统计连续天数 |
| 12 | UV 统计 | HyperLogLog |

### 扩展功能

- **接口限流**：秒杀用令牌桶（允许突发），普通接口用固定窗口；基于拦截器 + Redis Lua 实现，Redis 不可用时 fail-open
- **智能客服**：LLM Function Calling，模型可调用 5 个只读工具查询**当前用户自己**的订单 / 券 / 店铺；工具集是编译期固定的闭集，不支持任意 SQL
- **评论 CRUD**：发表 / 分页查询 / 修改 / 删除，校验归属并同步维护笔记评论数
- **登出**：删除 Redis 中的登录 token

## 快速开始

### 环境要求

- JDK 8
- Maven 3.6+
- MySQL 8.x
- Redis **6.2+**（第 7 章需要 Stream，第 10 章需要 `GEOSEARCH`）

> ⚠️ Redis 低于 5.0 不支持 `XADD`，低于 6.2 不支持 `GEOSEARCH`，秒杀与附近商户会直接报错。

### 1. 准备数据库

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS hmdp DEFAULT CHARSET utf8mb4;"
mysql -u root -p hmdp < src/main/resources/db/hmdp.sql
```

### 2. 启动 Redis

```bash
docker run -d --name hmdp-redis -p 6379:6379 redis:6.2

# 消费组必须手工创建，否则消费线程会一直报 NOGROUP
docker exec hmdp-redis redis-cli XGROUP CREATE stream.orders g1 0 MKSTREAM
```

### 3. 创建配置文件

`src/main/resources/application.yaml` 未被纳入版本控制（含凭据），需自行创建：

```yaml
server:
  port: 8081
spring:
  application:
    name: hmdp
  datasource:
    driver-class-name: com.mysql.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC
    username: root
    password: your-password
  redis:
    host: 127.0.0.1
    port: 6379
    # 无密码就留空，RedissonConfig 会跳过 AUTH
    password:
  jackson:
    default-property-inclusion: non_null
mybatis-plus:
  type-aliases-package: com.hmdp.entity
logging:
  level:
    com.hmdp: debug

# 秒杀异步消费开关；Redis 不支持 Stream 时可设为 false
hmdp:
  seckill:
    consumer-enabled: true
  # 智能客服（可选，不配则 /chat 返回“暂未开通”）
  ai:
    enabled: false
    base-url: https://api.deepseek.com
    api-key: ${HMDP_AI_API_KEY:}
    model: deepseek-chat
```

### 4. 启动

```bash
mvn clean package -DskipTests
mvn spring-boot:run
```

服务端口 `8081`。验证数据库是否连通：

```bash
curl http://localhost:8081/shop-type/list
```

## 项目结构

```
src/main/java/com/hmdp
├── controller      # 9 个 REST 控制器
├── service         # 业务接口 + impl
├── mapper          # MyBatis-Plus BaseMapper
├── entity / dto    # 实体与传输对象
├── config          # MyBatis / MVC / Redisson / 全局异常
├── utils           # CacheClient、RedisIdWorker、拦截器、限流、UserHolder
└── ai              # 智能客服：client / tool / memory / service
src/main/resources
├── seckill.lua               # 秒杀资格原子判断
├── unlock.lua                # 分布式锁原子解锁
├── rate_limit_*.lua          # 令牌桶 / 固定窗口
├── mapper/VoucherMapper.xml  # 唯一的手写 SQL
└── db/hmdp.sql               # 建表 + 初始数据
```

## Redis 设计

### 数据结构选型

| 结构 | 用途 | Key 示例 |
| --- | --- | --- |
| String | 验证码、缓存、库存、锁 | `login:code:{phone}`、`cache:shop:{id}` |
| Hash | 登录态、令牌桶状态 | `login:token:{token}` |
| Set | 关注关系、已下单用户 | `follows:{userId}`、`seckill:order:{voucherId}` |
| ZSet | 点赞榜、Feed 收件箱 | `blog:liked:{blogId}`、`feed:{userId}` |
| Stream | 异步下单消息队列 | `stream.orders` |
| GEO | 附近商户 | `shop:geo:{typeId}` |
| BitMap | 签到 | `sign:{userId}:yyyyMM` |
| HyperLogLog | UV 统计 | — |

### 关键设计

- **登录态 25 天滑动过期**：每次请求刷新 TTL，避免用户逛不需要登录的页面时掉线
- **缓存穿透用空值兜底**，击穿提供互斥锁与逻辑过期两套方案
- **秒杀全链路**：Lua 原子判资格 → Stream 异步落库 → pending-list 补偿
- **限流 key 带规则名**（`rate:{规则名}:{userId 或 IP}`），避免不同规则共用计数器

## 接口概览

| 模块 | 主要接口 |
| --- | --- |
| 用户 | `POST /user/code`、`POST /user/login`、`POST /user/logout`、`GET /user/me`、`POST /user/sign` |
| 商铺 | `GET /shop/{id}`、`GET /shop/of/type`、`GET /shop/of/name` |
| 秒杀 | `POST /voucher-order/seckill/{id}` |
| 探店 | `GET /blog/hot`、`GET /blog/{id}`、`PUT /blog/like/{id}`、`GET /blog/of/follow` |
| 关注 | `PUT /follow/{id}/{isFollow}`、`GET /follow/common/{id}` |
| 评论 | `POST /blog-comments`、`GET /blog-comments/of/blog/{blogId}` |
| 智能客服 | `POST /chat` |

所有接口统一返回 `{ success, errorMsg, data }`。

## 说明

- 本项目基于《黑马点评》课程实现，用于学习与个人实践
- 前端页面为独立项目，未包含在本仓库
- 除课程内容外，扩展了接口限流、智能客服、评论与登出功能
