-- 两个键共享账户 hash tag，支持 Redis Cluster。
-- KEYS[1] 订单键，KEYS[2] 请求ID绑定键。请求绑定保留 ARGV[5] 秒。
local binding = ARGV[1] .. ':' .. KEYS[1]
local previous = redis.call('GET', KEYS[2])
if previous and previous ~= binding then return {'CONFLICT', ''} end
-- 处理中不设 TTL，避免执行未完成时锁过期导致重投。
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('HSET', KEYS[1], 'fingerprint', ARGV[1], 'owner', ARGV[2],
        'state', 'PROCESSING', 'requestId', ARGV[3], 'createdAt', ARGV[4])
    redis.call('SET', KEYS[2], binding, 'EX', ARGV[5])
    return {'ACQUIRED', ''}
end
if redis.call('HGET', KEYS[1], 'fingerprint') ~= ARGV[1] then
    return {'CONFLICT', ''}
end
redis.call('SET', KEYS[2], binding, 'EX', ARGV[5])
local state = redis.call('HGET', KEYS[1], 'state')
if state == 'COMPLETED' then
    return {state, redis.call('HGET', KEYS[1], 'response') or ''}
end
if state == 'PROCESSING' or state == 'UNCERTAIN' then return {state, ''} end
return redis.error_reply('INVALID_IDEMPOTENCY_STATE')
