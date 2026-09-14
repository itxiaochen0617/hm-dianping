-- 比较线程标示与锁中的标示是否一致
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 一致，释放锁 del key
    return redis.call('del', KEYS[1])
end
-- 不一致，直接返回
return 0
