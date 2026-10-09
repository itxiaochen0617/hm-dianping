-- 固定窗口限流
-- KEYS[1] = 限流 key
-- ARGV[1] = 阈值 limit
-- ARGV[2] = 窗口秒数 windowSec
-- 返回：1 = 放行，0 = 被限流
--
-- 已知缺陷：窗口边界处可能通过阈值的 2 倍（比如 1 秒 100 次，
-- 第 1 秒最后 10 个 + 第 2 秒最初 100 个，实际 1.1 秒内过了 110 个）。
-- 普通接口可以接受，要消除得改用滑动窗口。

local current = redis.call('INCR', KEYS[1])
-- 只在第一次访问时设置过期。
-- 如果每次请求都 EXPIRE，TTL 会被不断刷新，这个窗口永远不会结束。
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end
if current > tonumber(ARGV[1]) then
    return 0
end
return 1
