if redis.call('EXISTS', KEYS[4]) == 1 then
  return -1
end

if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
  redis.call('ZINCRBY', KEYS[2], ARGV[2], ARGV[3])
  redis.call('EXPIRE', KEYS[2], ARGV[1])
  redis.call('INCRBY', KEYS[3], 1)
  redis.call('EXPIRE', KEYS[3], ARGV[1])
  redis.call('SET', KEYS[5], 'DATA', 'EX', ARGV[1])
  return 1
end
return 0
