-- 只有持有者可修改；不允许覆盖已完成结果。
if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 0 end
local state = redis.call('HGET', KEYS[1], 'state')
if state ~= 'PROCESSING' and state ~= 'UNCERTAIN' then return 0 end
if ARGV[2] == 'RELEASE' then
    if state ~= 'PROCESSING' then return 0 end
    return redis.call('DEL', KEYS[1])
end
if ARGV[2] == 'UNCERTAIN' then
    redis.call('HSET', KEYS[1], 'state', 'UNCERTAIN')
    redis.call('PERSIST', KEYS[1])
    return 1
end
if ARGV[2] == 'COMPLETED' then
    redis.call('HSET', KEYS[1], 'state', 'COMPLETED', 'response', ARGV[3])
    redis.call('EXPIRE', KEYS[1], ARGV[4])
    return 1
end
return redis.error_reply('INVALID_TRANSITION')
