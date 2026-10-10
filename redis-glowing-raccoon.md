# 接口限流（拦截器 + Redis）设计

> 对应 `优化改造清单.md` 的「改造项 3」。**本文档只做设计说明，通过后再实施。**

## Context

老师建议给接口加限流：**秒杀接口用令牌桶**（允许突发）、**普通接口用固定窗口**（简单够用）。

项目当前**没有任何限流**。直接用「自定义注解 + AOP」实现会踩坑——`pom.xml` 里没有 `spring-boot-starter-aop`，而项目前几天刚因为 `@EnableAspectJAutoProxy` 缺 AspectJ 运行时**启动崩溃**过一次。所以用**拦截器**实现，与现有的 `LoginInterceptor` / `RefreshTokenInterceptor` 保持同一套模式，**不引入任何新依赖**。

**目标**：超过阈值的请求被拒绝（HTTP 429），且**不消耗后端资源**（秒杀的 Redis 库存不会被扣）。

---

## 一、整体链路：拦截器插在哪

```
HTTP 请求
   ↓
① RefreshTokenInterceptor   order(0)  解析 token → UserHolder 填当前用户
   ↓
② LoginInterceptor          order(1)  没登录 → 401 中断
   ↓
③ RateLimitInterceptor      order(2)  【新增】限流，超限 → 429 中断
   ↓
④ Controller
```

**为什么放 order(2)（最后）**：

- order(0) 已经把用户放进 `UserHolder`，所以 order(2) 能拿到 `userId` 做「按用户限流」
- 没通过登录校验的请求在 order(1) 就被拦掉了，**不消耗限流配额**（401 本身很便宜，只是 ThreadLocal 判空）
- 注册方式沿用 `MvcConfig` 现有风格：`new RateLimitInterceptor(stringRedisTemplate)`，`addPathPatterns("/**")`

> **注意**：`LoginInterceptor` 用 `excludePathPatterns` 排除了 7 组路径，但**限流拦截器不排除任何路径**——公开接口（`/shop/**`、`/user/code`）同样是攻击面。

---

## 二、两种算法与对应的 Lua 脚本

### 2.1 固定窗口（普通接口）

**原理**：每个时间窗口一个计数器，`INCR` 到超过阈值就拒绝。

```lua
-- rate_limit_fixed_window.lua
-- KEYS[1] = 限流 key
-- ARGV[1] = 阈值 limit
-- ARGV[2] = 窗口秒数 windowSec
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])   -- 第一次访问才设置过期
end
if current > tonumber(ARGV[1]) then
    return 0                                  -- 被限流
end
return 1                                      -- 放行
```

**要点**：`INCR` 和 `EXPIRE` 必须在一起（脚本原子），且**只在 `current == 1` 时设过期**——否则每次请求都刷新 TTL，窗口永远不过期。

**已知缺陷**（文档里也提过）：窗口边界处可能通过阈值的 2 倍。普通接口可以接受，不修。

### 2.2 令牌桶（秒杀接口）

**原理**：桶按固定速率生成令牌，请求来了取一个，取不到就拒绝。
- **速率**决定长期平均；**容量**决定允许的突发量

```lua
-- rate_limit_token_bucket.lua
-- KEYS[1] = 限流 key（Hash：tokens / ts）
-- ARGV[1] = 桶容量 capacity
-- ARGV[2] = 生成速率 rate（个/秒）
-- ARGV[3] = 当前时间戳（毫秒）
-- ARGV[4] = 本次需要的令牌数（一般为 1）

local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])

-- 首次访问：桶是满的
if tokens == nil then
    tokens = capacity
    ts = now
end

-- 按经过的时间补充令牌
local delta = math.max(0, now - ts)
tokens = math.min(capacity, tokens + delta * rate / 1000)

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', KEYS[1], 60000)   -- 冷 key 自动回收

return allowed
```

### ⚠️ 相对文档的一处修正

`优化改造清单.md:469` 写的是：

```lua
local refill = math.floor(delta * rate / 1000)   -- ← 取整
tokens = math.min(capacity, tokens + refill)
...
lastRefill = now                                  -- ← 无条件推进
```

**这个版本会丢令牌**：`math.floor` 把不足 1 个的零头舍掉，而 `ts` 又被推到 `now`，零头就永久丢失了。

举例：速率 10/秒，每 150ms 来一个请求 → 每次算得 1.5 个令牌 → `floor` 成 1 → 每次丢 0.5 → 实际速率只有 **6.67/秒**，不是 10/秒。

**修法**：`tokens` 用**浮点数**存，不做 `floor`（上面脚本已修正）。

### 2.3 为什么必须写 Lua

「读令牌 → 算补充 → 判断 → 扣减 → 写回」如果分成多条命令，并发下会**超发**（多个请求同时读到"够用"）。
Lua 脚本在 Redis 里原子执行——和 `seckill.lua` 是同一个道理。

---

## 三、限流键的设计

| 规则 | key 模式 | 维度 |
|---|---|---|
| 秒杀 | `rate:seckill:user:{userId}` | 按用户 |
| 普通接口 | `rate:api:ip:{ip}` | 按 IP |
| 短信验证码 | `rate:code:ip:{ip}` | 按 IP |

**维度选择逻辑**：

```
规则声明维度 = USER  →  取 UserHolder.getUser().getId()
                        若为 null（未登录）→ 自动降级为按 IP
规则声明维度 = IP    →  取客户端 IP
```

**客户端 IP 的取法**（⚠️ 有坑）：

```java
// 优先取 X-Forwarded-For（经过 nginx 代理时，remoteAddr 拿到的是 nginx 的 IP）
String xff = request.getHeader("X-Forwarded-For");
ip = (xff != null && !xff.isEmpty()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
```

> **⚠️ XFF 可伪造**：客户端可以自己填 `X-Forwarded-For`。生产环境应该只信任来自可信代理的 XFF，或改用 nginx 的 `X-Real-IP` + 内部网络校验。本项目作为学习用途，先按上面的写法，但要**在代码注释里写明这是个已知弱点**。

---

## 四、规则表

规则**按顺序全部评估**（不是首个匹配就停）——这样能对同一路径叠加「IP 粗限流 + 用户细限流」两层。

```java
// ① 秒杀：令牌桶。容量 5（允许突发 5 次），速率 1/秒，按用户
rule("/voucher-order/seckill/**", TOKEN_BUCKET, USER, capacity = 5, rate = 1)

// ② 秒杀 IP 兜底：固定窗口。防止有人用大量账号绕过按用户限流（暂不启用，见下）
// rule("/voucher-order/seckill/**", FIXED_WINDOW, IP, limit = 20, windowSec = 1)

// ③ 短信验证码：固定窗口。5 次/分钟，按 IP（防短信轰炸）
rule("/user/code", FIXED_WINDOW, IP, limit = 5, windowSec = 60)

// ④ 全局兜底：固定窗口。100 次/秒，按 IP
rule("/**", FIXED_WINDOW, IP, limit = 100, windowSec = 1)
```

**路径匹配**用 Spring 自带的 `org.springframework.util.AntPathMatcher`，不用引新库。

**阈值是占位值**，实施后要压测调整（文档 3.6 ⑤ 也强调了这点）。

---

## 五、被限流时的响应

用 **HTTP 429** + JSON body：

```
HTTP/1.1 429 Too Many Requests
Content-Type: application/json;charset=UTF-8

{"success":false,"errorMsg":"请求过于频繁，请稍后再试"}
```

**为什么是 429 而不是 HTTP 200 + `Result.fail`**：

- 项目里业务失败确实走 HTTP 200 + `Result.fail`（见 `WebExceptionAdvice`）
- 但**限流不是业务失败，是协议层面的"你被限速了"**，429 是标准状态码
- 而且 `LoginInterceptor` 已经开了「用 HTTP 状态码表达拦截」的先例（它返回 401）

**实现上要注意**：`LoginInterceptor` 只 `setStatus` 不写 body。限流这里要**手动写 body**：

```java
response.setStatus(429);
response.setContentType("application/json;charset=UTF-8");
response.getWriter().write(JSONUtil.toJsonStr(Result.fail("请求过于频繁，请稍后再试")));
return false;   // 中断请求，不进 Controller
```

---

## 六、⚠️ 关键设计决策：Redis 挂了怎么办

限流拦截器会**跑在每个请求上**。如果它对所有路径生效，而 Redis 又挂了——拦截器抛异常会让**全站 500**，比限流本身危害大得多。

**决策：失败放行（fail-open）**

```java
try {
    // 执行限流判断
} catch (Exception e) {
    // Redis 不可用时放行，只记警告。宁可漏限流，不可让全站不可用
    log.warn("限流检查失败，放行本次请求", e);
    return true;
}
```

这是限流器的通行做法：**可用性优先于保护性**。要在注释里写明这是**有意为之**，不是漏了 try-catch。

> 反过来说，如果业务要求"宁可拒绝也不放行"（比如支付），那才用 fail-close。本项目选 fail-open。

---

## 七、要新增/修改的文件

**新增**（5 个）：

| 文件 | 职责 |
|---|---|
| `resources/rate_limit_fixed_window.lua` | 固定窗口脚本 |
| `resources/rate_limit_token_bucket.lua` | 令牌桶脚本 |
| `utils/RateLimitAlgorithm.java` | 枚举：`FIXED_WINDOW` / `TOKEN_BUCKET` |
| `utils/RateLimitRule.java` | 规则对象：路径模式、算法、维度、阈值参数 |
| `utils/RateLimitInterceptor.java` | 拦截器主体：规则表 + 路径匹配 + 调 Lua + 写 429 |

**修改**（2 个，都是小改）：

| 文件 | 改动 |
|---|---|
| `config/MvcConfig.java` | 注册第三个拦截器 `order(2)` |
| `utils/RedisConstants.java` | 加三组 key 前缀：`rate:seckill:user:`、`rate:api:ip:`、`rate:code:ip:` |

**不改**：任何 Controller、任何 Service、`pom.xml`、启动类。

**复用现有代码**：
- `StringRedisTemplate` — 已在 `MvcConfig` 里 `@Resource` 注入，直接传给拦截器构造函数
- `UserHolder.getUser()` — 已有的 ThreadLocal，取当前用户
- `Result.fail(String)` — 已有的响应包装
- `RedisConstants` — key 常量的既有归属地

---

## 八、验证方式

**编译**：
```powershell
$env:JAVA_HOME="C:\Users\29187\.jdks\corretto-1.8.0_482"
& "D:\JetBrains\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" -o clean test-compile
```

**接口验证**（Redis 走 Docker 的 6.2，应用 8081）：

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 连续快速调 `POST /voucher-order/seckill/10`（带 token）超过 5 次 | 前几次正常，之后返回 **429** |
| 2 | 查看 Redis `HGETALL rate:seckill:user:{userId}` | 能看到 `tokens`（浮点数）和 `ts` 两个字段 |
| 3 | 等 3 秒再调一次 | 恢复正常（令牌补充了） |
| 4 | **关键**：限流生效时对比 `GET seckill:stock:10` | **库存没有继续减少** → 证明请求真被挡住了，不是白限 |
| 5 | 快速调 `POST /user/code?phone=...` 超过 5 次 | 返回 429（防短信轰炸生效） |
| 6 | 不带 token 调 `/user/me` | 仍返回 **401**（不是 429）→ 证明限流在登录校验**之后** |

**边界验证**：

| # | 步骤 | 期望 |
|---|---|---|
| 7 | 停掉 Redis 容器后再调接口 | **不返回 500**，请求正常放行，日志有 `限流检查失败，放行本次请求` 的警告 |
| 8 | 查看连续请求的日志 | 被限流的请求有日志记录，便于排查 |

---

## 九、已知局限（写在代码注释里）

| 局限 | 说明 |
|---|---|
| **XFF 可伪造** | 按 IP 限流的规则可被绕过，生产需校验可信代理 |
| **固定窗口有临界问题** | 边界处可能通过 2 倍阈值，普通接口可接受 |
| **令牌桶 `PEXPIRE 60s`** | 用户空闲 60 秒后桶会重置为满，属于预期行为 |
| **阈值是估算值** | 未经压测，实际生产要按 QPS 能力调整 |
| **单机 Redis** | 限流计数集中在单个 Redis 上，它挂了限流就失效（此时 fail-open） |

---

## 十、实施顺序

1. 写两个 Lua 脚本
2. 写 `RateLimitAlgorithm` + `RateLimitRule`（纯数据类）
3. 写 `RateLimitInterceptor`（核心逻辑）
4. 改 `RedisConstants` 加 key 常量
5. 改 `MvcConfig` 注册拦截器
6. 编译 → 启动 → 按第八节逐条验证

**预计改动量**：新增约 4 个 Java 文件（合计约 200 行）+ 2 个 Lua 脚本（合计约 40 行）+ 2 处小改。
