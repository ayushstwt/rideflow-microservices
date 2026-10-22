-- Atomically releases the driver held for a ride. Safe to call any number of times.
--
-- KEYS[1]  ride:assignment:{rideId}      value = driverId
-- ARGV[1]  rideId
-- ARGV[2]  prefix of the driver reservation keys ("driver:reservation:"), value = rideId
--
-- The driver key is deleted only while it still holds this rideId: if the TTL already expired and
-- the driver was reserved for another ride, that newer reservation must stay untouched.
-- Returns the released driverId, or false when the ride had no assignment.

local driverId = redis.call('GET', KEYS[1])
if not driverId then
    return false
end

local driverKey = ARGV[2] .. driverId
if redis.call('GET', driverKey) == ARGV[1] then
    redis.call('DEL', driverKey)
end
redis.call('DEL', KEYS[1])
return driverId
