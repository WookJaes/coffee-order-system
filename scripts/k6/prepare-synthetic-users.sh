#!/usr/bin/env bash
set -euo pipefail

# Creates users only. k6 setup() charges these users before issuing orders.
# Usage: USER_COUNT=8 scripts/k6/prepare-synthetic-users.sh

COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-.env}"
USER_COUNT="${USER_COUNT:-8}"
USERS_FILE="${USERS_FILE:-build/k6/synthetic-users.json}"
RUN_LABEL="k6-$(date +%s)-$$"

if ! [[ "$USER_COUNT" =~ ^[1-9][0-9]*$ ]]; then
  echo 'USER_COUNT는 1 이상의 정수여야 합니다.' >&2
  exit 1
fi

if [[ ! -f "$COMPOSE_ENV_FILE" ]]; then
  echo "환경 파일을 찾을 수 없습니다: $COMPOSE_ENV_FILE" >&2
  exit 1
fi

set -a
source "$COMPOSE_ENV_FILE"
set +a

mysql() {
  docker compose --env-file "$COMPOSE_ENV_FILE" exec -T mysql \
    mysql -N -B -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "$1"
}

user_ids=()
for ((index = 1; index <= USER_COUNT; index++)); do
  user_id="$(mysql "INSERT INTO users (name, created_at, updated_at) VALUES ('${RUN_LABEL}-${index}', NOW(), NOW()); SELECT LAST_INSERT_ID();" | tail -n 1)"
  if ! [[ "$user_id" =~ ^[1-9][0-9]*$ ]]; then
    echo "synthetic 사용자 생성에 실패했습니다: index=$index" >&2
    exit 1
  fi
  user_ids+=("$user_id")
done

mkdir -p "$(dirname "$USERS_FILE")"
ids_json="[$(IFS=,; echo "${user_ids[*]}")]"
jq -n --arg runLabel "$RUN_LABEL" --argjson userIds "$ids_json" \
  '{runLabel: $runLabel, users: [$userIds[] | {userId: .}]}' >"$USERS_FILE"

printf 'synthetic 사용자 %s명을 %s에 기록했습니다.\n' "$USER_COUNT" "$USERS_FILE"
