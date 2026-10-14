-- Deletes a lock only if we still own it (value = our random token). A plain DEL could remove a
-- lock that already expired and was taken by another pod.
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
