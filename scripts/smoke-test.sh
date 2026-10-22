#!/usr/bin/env bash
# End-to-end smoke test for a RUNNING rideflow stack (Redis, MySQL, Kafka and the three services up).
# Meant for CI (Step 11); it is not run by `mvn test` or `mvn verify`.
#
#   LOCATION_URL  default http://localhost:8082
#   RIDE_URL      default http://localhost:8083
#   MATCHING_URL  default http://localhost:8084 (only used for the health check)
#
# Requires: bash, curl, jq. Exits non-zero on the first failed step.
set -euo pipefail

LOCATION_URL="${LOCATION_URL:-http://localhost:8082}"
RIDE_URL="${RIDE_URL:-http://localhost:8083}"
MATCHING_URL="${MATCHING_URL:-http://localhost:8084}"

HEALTH_TIMEOUT_SECONDS=90
ACCEPT_TIMEOUT_SECONDS=60
RELEASE_TIMEOUT_SECONDS=30

PICKUP_LAT="12.9716"
PICKUP_LON="77.5946"

# Unique per run so reruns against the same stack do not collide.
RUN_ID="$(date +%s)-$$"
RIDER_ID="smoke-rider-${RUN_ID}"
IDEMPOTENCY_KEY="smoke-key-${RUN_ID}"
DRIVER_IDS=("smoke-driver-${RUN_ID}-1" "smoke-driver-${RUN_ID}-2" "smoke-driver-${RUN_ID}-3")
RIDE_ID=""

pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

for tool in curl jq; do
  command -v "$tool" >/dev/null 2>&1 || fail "required tool '$tool' is not installed"
done

cleanup() {
  # Best effort: leave no smoke drivers (or an active ride) behind.
  local status=$?
  if [[ -n "$RIDE_ID" ]]; then
    curl -s -o /dev/null --max-time 5 -X PUT "${RIDE_URL}/api/v1/rides/${RIDE_ID}/cancel" || true
  fi
  for driver in "${DRIVER_IDS[@]}"; do
    curl -s -o /dev/null --max-time 5 -X DELETE "${LOCATION_URL}/api/v1/locations/drivers/${driver}" || true
  done
  exit "$status"
}
trap cleanup EXIT

# Runs curl with sane defaults and fails (via set -e + fail) on connection errors or HTTP >= 400.
api() { curl -fsS --max-time 10 "$@"; }

wait_for_health() {
  local name="$1" url="$2"
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  until [[ "$(curl -s --max-time 3 "${url}/actuator/health" | jq -r '.status // empty' 2>/dev/null || true)" == "UP" ]]; do
    if (( SECONDS >= deadline )); then
      fail "${name} did not report UP at ${url}/actuator/health within ${HEALTH_TIMEOUT_SECONDS}s"
    fi
    sleep 2
  done
  pass "${name} is UP"
}

register_drivers() {
  local i=0 offset
  for driver in "${DRIVER_IDS[@]}"; do
    i=$((i + 1))
    # A few hundred metres apart, all well inside the 5 km search radius.
    offset="0.00${i}"
    api -X POST "${LOCATION_URL}/api/v1/locations/drivers/update" \
      -H 'Content-Type: application/json' \
      -d "$(jq -n --arg id "$driver" --argjson lat "$PICKUP_LAT" --argjson lon "$PICKUP_LON" --argjson off "$offset" \
            '{driverId: $id, latitude: ($lat + $off), longitude: ($lon + $off)}')" >/dev/null \
      || fail "could not register driver ${driver}"
  done
}

nearby_driver_ids() {
  api "${LOCATION_URL}/api/v1/locations/drivers/nearby?latitude=${PICKUP_LAT}&longitude=${PICKUP_LON}&radius=5&limit=50" \
    | jq -r '.[].driverId'
}

ride_request_body() {
  jq -n --arg rider "$RIDER_ID" --argjson lat "$PICKUP_LAT" --argjson lon "$PICKUP_LON" '{
    riderId: $rider,
    pickupLatitude: $lat, pickupLongitude: $lon, pickupAddress: "Smoke Test Pickup",
    dropLatitude: 13.1986, dropLongitude: 77.7066, dropAddress: "Smoke Test Drop"
  }'
}

# 1. health ---------------------------------------------------------------------------------------------------
wait_for_health "location-service" "$LOCATION_URL"
wait_for_health "ride-service" "$RIDE_URL"
wait_for_health "matching-service" "$MATCHING_URL"

# 2. drivers --------------------------------------------------------------------------------------------------
register_drivers
visible="$(nearby_driver_ids)"
for driver in "${DRIVER_IDS[@]}"; do
  grep -qx "$driver" <<<"$visible" || fail "driver ${driver} is not returned by nearby after registration"
done
pass "3 drivers registered and visible in nearby"

# 3. ride request with an Idempotency-Key ---------------------------------------------------------------------
response="$(api -X POST "${RIDE_URL}/api/v1/rides/request" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -d "$(ride_request_body)")" || fail "ride request was rejected"
RIDE_ID="$(jq -r '.id // empty' <<<"$response")"
[[ -n "$RIDE_ID" ]] || fail "ride request returned no id: ${response}"
pass "ride ${RIDE_ID} requested"

replay_code="$(curl -s -o /tmp/smoke-replay.$$ -w '%{http_code}' --max-time 10 -X POST "${RIDE_URL}/api/v1/rides/request" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" -d "$(ride_request_body)")"
replay_id="$(jq -r '.id // empty' /tmp/smoke-replay.$$ 2>/dev/null || true)"
rm -f /tmp/smoke-replay.$$
[[ "$replay_code" == "200" && "$replay_id" == "$RIDE_ID" ]] \
  || fail "replaying the Idempotency-Key should return 200 and the same ride (got ${replay_code}, ${replay_id})"
pass "same Idempotency-Key returned the same ride with 200"

# 4. wait until a driver is assigned --------------------------------------------------------------------------
deadline=$((SECONDS + ACCEPT_TIMEOUT_SECONDS))
status=""
while :; do
  status_body="$(api "${RIDE_URL}/api/v1/rides/${RIDE_ID}/status")" || fail "could not read the ride status"
  status="$(jq -r '.status' <<<"$status_body")"
  [[ "$status" == "ACCEPTED" ]] && break
  [[ "$status" == "NO_DRIVER_FOUND" || "$status" == "CANCELLED" ]] && fail "ride ended as ${status} instead of ACCEPTED"
  (( SECONDS < deadline )) || fail "ride is still ${status} after ${ACCEPT_TIMEOUT_SECONDS}s"
  sleep 2
done
ASSIGNED_DRIVER="$(jq -r '.driverId // empty' <<<"$status_body")"
[[ -n "$ASSIGNED_DRIVER" ]] || fail "ride is ACCEPTED but has no driverId"
pass "ride ACCEPTED with driver ${ASSIGNED_DRIVER}"

# 5. the assigned driver must be hidden from nearby -----------------------------------------------------------
if nearby_driver_ids | grep -qx "$ASSIGNED_DRIVER"; then
  fail "assigned driver ${ASSIGNED_DRIVER} is still returned by nearby"
fi
pass "assigned driver is no longer returned by nearby"

# 6. cancel, then the driver must come back -------------------------------------------------------------------
# Heartbeats last only ~30s and the steps above may have taken a while, so refresh them first.
register_drivers
api -X PUT "${RIDE_URL}/api/v1/rides/${RIDE_ID}/cancel" >/dev/null || fail "could not cancel ride ${RIDE_ID}"
pass "ride cancelled"

deadline=$((SECONDS + RELEASE_TIMEOUT_SECONDS))
until nearby_driver_ids | grep -qx "$ASSIGNED_DRIVER"; do
  (( SECONDS < deadline )) || fail "driver ${ASSIGNED_DRIVER} was not released within ${RELEASE_TIMEOUT_SECONDS}s of the cancel"
  sleep 1
done
pass "driver ${ASSIGNED_DRIVER} is returned by nearby again"

echo "ALL CHECKS PASSED"
