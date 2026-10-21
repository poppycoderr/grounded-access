#!/usr/bin/env bash
# usage: scenarios.sh <repo checkout> <compose project> <output dir>
set -uo pipefail
repo=$1; project=$2; out=$3
mkdir -p "$out"
cd "$repo"
docker compose -p "$project" up -d --build --wait > "$out/up.log" 2>&1 || { echo "up failed"; exit 1; }
./scripts/load-demo > "$out/load-demo.log" 2>&1 || { echo "load failed"; exit 1; }
eval_cli() { uv run --project packages/evaluation ga-eval "$@"; }
eval_cli load-test --strategy hybrid-rrf --workers 16 --requests 400 --allow-degraded --out "$out/queries" > "$out/queries.log" 2>&1
( cd "$repo/packages/evaluation" && uv run python "$(dirname "$0")/flood.py" ../../data http://localhost:8080 90 > "$out/flood.log" 2>&1 ) &
flood=$!
sleep 10
eval_cli load-test --strategy hybrid-rrf --workers 16 --requests 400 --allow-degraded --out "$out/queries-during-ingestion" > "$out/queries-during-ingestion.log" 2>&1
wait $flood
eval_cli load-test --strategy hybrid-rrf --workers 16 --requests 400 --allow-degraded --out "$out/queries-after" > "$out/queries-after.log" 2>&1
eval_cli load-test --strategy hybrid-rrf-rerank --workers 8 --requests 60 --allow-degraded --out "$out/rerank-8" > "$out/rerank-8.log" 2>&1
docker compose -p "$project" down -v --rmi local > /dev/null 2>&1
echo "done"
