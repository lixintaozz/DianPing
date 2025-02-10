-- 检验Redis中存储的标识和线程保存的标识是否一致
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    redis.call('del', KEYS[1])
end
return 0