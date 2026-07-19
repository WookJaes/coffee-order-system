local snapshots = {}

for index = 1, #KEYS, 3 do
  local ranking_key = KEYS[index]
  local status_key = KEYS[index + 1]
  local count_key = KEYS[index + 2]
  local status = redis.call('GET', status_key) or ''
  local count = redis.call('GET', count_key) or ''
  local ranking_exists = redis.call('EXISTS', ranking_key)
  local entries = redis.call('ZRANGE', ranking_key, 0, -1, 'WITHSCORES')
  local menu_scores = {}
  for entry_index = 1, #entries, 2 do
    menu_scores[#menu_scores + 1] = entries[entry_index] .. '=' .. entries[entry_index + 1]
  end
  snapshots[#snapshots + 1] = status .. '|' .. count .. '|' .. ranking_exists .. '|' .. table.concat(menu_scores, ',')
end

return table.concat(snapshots, ';')
