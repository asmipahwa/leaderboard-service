#!/usr/bin/env python3
"""Submits random match results so the live demo page has something to show.

Usage: python3 demo/bot.py [--game fc-mobile] [--board weekly-high-score] [--rate 20]
Uses only the standard library and the REST facade (http://localhost:8080 by default).
"""
import argparse
import json
import random
import time
import urllib.request
import uuid


def call(url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}, method="POST" if data else "GET"
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.loads(response.read())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="http://localhost:8080")
    parser.add_argument("--game", default="fc-mobile")
    parser.add_argument("--board", default="all-time-high-score")
    parser.add_argument("--rate", type=float, default=20, help="submissions per second")
    args = parser.parse_args()

    base = f"{args.host}/api/v1/games/{args.game}/leaderboards/{args.board}"
    # Contenders: real players from around the top of the board, so new scores reshuffle the top 10.
    top = call(f"{base}/top?n=200")
    players = [entry["playerId"] for entry in top["entries"]]
    if not players:
        raise SystemExit("Board is empty; run `make seed` first.")
    # Anchor on today's top score so results stay realistic instead of compounding upward.
    anchor = top["entries"][0]["score"]
    print(f"Submitting ~{args.rate}/s for {len(players)} players on {args.game}/{args.board} (Ctrl+C to stop)")

    sent = 0
    while True:
        player = random.choice(players)
        # Mostly just below the current record, sometimes a new one.
        score = int(anchor * random.uniform(0.85, 1.10))
        result = call(
            f"{base}/scores",
            {"playerId": player, "score": score, "idempotencyKey": f"bot-{uuid.uuid4()}", "win": random.random() < 0.5},
        )
        sent += 1
        if result["improved"] and result["rank"] <= 10:
            print(f"#{sent}: {player[:8]} scored {score:,} -> rank {result['rank']} (was {result['previousRank']})")
        time.sleep(1 / args.rate)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
