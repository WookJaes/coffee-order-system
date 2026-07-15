if redis.call('GET', KEYS[1]) ~= ARGV[1] then
  return -1
end

if ARGV[2] == 'CLEAR' then
  return redis.call('DEL', unpack(KEYS, 2))
end

if ARGV[2] == 'MARKER' then
  if redis.call('SET', KEYS[2], ARGV[1], 'NX', 'EX', ARGV[3]) then
    return 1
  end
  return 0
end

if ARGV[2] == 'RANKING' then
  redis.call('ZADD', KEYS[2], ARGV[4], ARGV[5])
  redis.call('EXPIRE', KEYS[2], ARGV[3])
  return 1
end

if ARGV[2] == 'STATUS' then
  redis.call('SET', KEYS[2], ARGV[4], 'EX', ARGV[3])
  return 1
end

return -1
