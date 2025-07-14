--- 所需参数：voucher_id, user_id, order_id
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

--- 要是用的key
local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId

--- 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end

--- 判断用户是否下过单
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

--- 允许下单，扣减库存，保存用户
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

--- 发送给消息队列 XADD stream.orders * k1 v1 ...
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
