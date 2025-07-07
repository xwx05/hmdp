--- 获取锁中的线程标识
local id = redis.call('GET', KEYS[1])
--- 判断是否与当前线程标识一致
if (id == ARGV[1]) then
    --- 一致则释放锁
    return redis.call('DEL', KEYS[1])
end
return 0
