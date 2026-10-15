#!/bin/sh
set -eu

existing_card_count="$(psql -v ON_ERROR_STOP=1 -t -A -c 'SELECT COUNT(*) FROM public.global_cards;')"

if [ "$existing_card_count" -gt 0 ]; then
  echo "Development seed skipped: global_cards already contains $existing_card_count rows."
  exit 0
fi

psql \
  -v ON_ERROR_STOP=1 \
  --single-transaction \
  --file /seed/02-data.sql \
  --file /seed/03-profile-avatar.sql

echo "Development seed applied successfully."
