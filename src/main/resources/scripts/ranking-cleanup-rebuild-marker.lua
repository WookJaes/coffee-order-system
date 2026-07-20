if redis.call('GET', KEYS[1]) == ARGV[1] and redis.call('GET', KEYS[2]) == ARGV[1] then
	return redis.call('DEL', KEYS[2])
end
return 0
