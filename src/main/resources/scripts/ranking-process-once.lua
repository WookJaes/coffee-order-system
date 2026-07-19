if redis.call('EXISTS', KEYS[4]) == 1 then
  return -1
end

if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end

local rankingType = redis.call('TYPE', KEYS[2]).ok
local countType = redis.call('TYPE', KEYS[3]).ok
local statusType = redis.call('TYPE', KEYS[5]).ok
if (rankingType ~= 'none' and rankingType ~= 'zset')
  or (countType ~= 'none' and countType ~= 'string')
  or (statusType ~= 'none' and statusType ~= 'string') then
  return -2
end

local countValue = redis.call('GET', KEYS[3])
if countValue then
  local count = tonumber(countValue)
  if not count or count < 0 or count % 1 ~= 0 or count >= 9223372036854775807 then
    return -2
  end
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
