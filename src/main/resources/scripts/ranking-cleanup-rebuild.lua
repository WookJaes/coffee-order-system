if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', unpack(KEYS, 2))
end
return 0
