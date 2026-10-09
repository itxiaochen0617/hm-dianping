-- 令牌桶限流
-- KEYS[1] = 限流 key（Hash：tokens = 当前令牌数，ts = 上次计算时间戳）
-- ARGV[1] = 桶容量 capacity（决定允许的突发量）
-- ARGV[2] = 生成速率 rate（个/秒，决定长期平均速率）
-- ARGV[3] = 当前时间戳（毫秒）
-- ARGV[4] = 本次需要的令牌数（一般为 1）
-- 返回：1 = 放行，0 = 被限流
--
-- 为什么整个逻辑必须写在 Lua 里：
-- 「读令牌 → 算补充 → 判断 → 扣减 → 写回」如果拆成多条命令，
-- 并发下多个请求会同时读到"够用"，导致超发。Lua 保证这几步原子执行。

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

-- 按经过的时间补充令牌。
-- ⚠️ tokens 用浮点数保存、不做 math.floor 取整：
--    取整会把不足 1 个的零头丢掉，而下面 ts 又会被推进到 now，
--    零头就永久丢失了。例如速率 10/秒、每 150ms 来一个请求，
--    每次算得 1.5 个、取整成 1 个，实际速率只有 6.67/秒。
local delta = math.max(0, now - ts)
tokens = math.min(capacity, tokens + delta * rate / 1000)

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- 冷 key 自动回收：用户空闲 60 秒后桶被清掉，下次访问重新满桶
redis.call('PEXPIRE', KEYS[1], 60000)

return allowed
