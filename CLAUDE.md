# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目状态（先读这一段）

这是《黑马点评》实战课程的**初始骨架工程**，不是完成品。大量业务逻辑是 `// TODO` 桩，默认返回 `Result.fail("功能未完成")`。改动前先确认你面对的是桩代码还是已实现代码。

已实现：
- **第1章 短信登录**：`UserController` 的 `code`/`login`/`me`、`UserServiceImpl`（Redis token 登录）、`utils/LoginInterceptor`、`utils/RefreshTokenInterceptor`、`config/MvcConfig`
- `ShopController`（基础 CRUD、按类型/名称分页）、`ShopTypeController`、`UploadController`
- `VoucherController` + `VoucherServiceImpl.addSeckillVoucher/queryVoucherOfShop`（含 `VoucherMapper.xml` 手写联表查询）
- `BlogController` 的 `queryHotBlog`；`BlogController.saveBlog/likeBlog/queryMyBlog` 只做了最朴素的数据库更新（`liked = liked + 1`），没有点赞去重、没有 Feed 推送

未实现（纯桩）：
- `UserController.logout` —— 讲义本身没有实现它，参考实现也保留为 TODO
- `VoucherOrderController.seckillVoucher`（秒杀下单）
- `FollowController`、`BlogCommentsController` —— 空类
- `ShopServiceImpl`、`BlogServiceImpl`、`FollowServiceImpl`、`VoucherOrderServiceImpl` 等 —— 空壳，只继承 MP 的 `ServiceImpl`

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

## 技术栈

Spring Boot 2.3.12 / Java 8 / Maven 单模块。MyBatis-Plus 3.4.3、MySQL Connector 5.1.47、spring-boot-starter-data-redis（Lettuce + commons-pool2）、Hutool 5.7.17、Lombok。

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
| `LOCK_SHOP_KEY` / `LOCK_SHOP_TTL` | `lock:shop:` / 10 | 商铺缓存互斥锁 |
| `SECKILL_STOCK_KEY` | `seckill:stock:` | 秒杀库存 |
| `BLOG_LIKED_KEY` | `blog:liked:` | 点赞 Set |
| `FEED_KEY` | `feed:` | 关注 Feed 收件箱 |
| `SHOP_GEO_KEY` | `shop:geo:` | 店铺 GEO |
| `USER_SIGN_KEY` | `sign:` | 签到 BitMap |

`utils/RedisData`（`expireTime` + `data`）是为**逻辑过期**缓存方案预留的载体，目前无人使用。

## 数据库表

`tb_user`、`tb_user_info`、`tb_shop`、`tb_shop_type`、`tb_voucher`、`tb_seckill_voucher`、`tb_voucher_order`、`tb_blog`、`tb_blog_comments`、`tb_follow`。秒杀券是 `tb_voucher` + `tb_seckill_voucher` 两张表（后者存 stock/begin_time/end_time），`VoucherMapper.xml` 已写好在 `queryVoucherOfShop` 里做左连接。

数据库初始化脚本在 `src/main/resources/db/hmdp.sql`（Navicat 导出的完整建表语句 + 初始数据）。
