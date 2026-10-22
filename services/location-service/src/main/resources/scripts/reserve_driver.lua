-- Atomically reserves the first free driver of an ordered candidate list for a ride.
--
-- KEYS[1]  ride:assignment:{rideId}      value = driverId
-- ARGV[1]  rideId
-- ARGV[2]  reservation TTL in seconds
-- ARGV[3]  prefix of the driver reservation keys ("driver:reservation:"), value = rideId
-- ARGV[4+] candidate driverIds, best first
--
-- Returns the reserved driverId, or false (nil in Java) when every candidate is taken.
-- Redis runs the whole script without interleaving other commands, so two rides can never
-- both see the same driver as free.

-- Idempotent: the same ride always gets the same driver, even if the request is processed twice.
local existing = redis.call('GET', KEYS[1])
if existing then
    return existing
end

local rideId = ARGV[1]
local ttl = tonumber(ARGV[2])
local prefix = ARGV[3]

for i = 4, #ARGV do
    local driverKey = prefix .. ARGV[i]
    if redis.call('EXISTS', driverKey) == 0 then
        redis.call('SET', driverKey, rideId, 'EX', ttl)
        redis.call('SET', KEYS[1], ARGV[i], 'EX', ttl)
        return ARGV[i]
    end
end

return false
