#!/usr/bin/env python3
"""Discover Telegram chat IDs for a bot by dumping all message/chat IDs.

Poll getUpdates and print every update's update_id, message_id, chat_id,
chat type, sender, and text snippet. The last line per update is the chat
ID people actually want to copy.

Note: getUpdates only sees messages received *after* the bot starts polling,
and only for chats where someone already messaged the bot. Message the bot
(`/start`) first. The getUpdates offset advances automatically, so old messages
are not re-dumped on a re-run.

Usage:
  python3 telegram_chat_ids.py --token 123456:ABC
  python3 telegram_chat_ids.py --token 123456:ABC --repeat --seconds 60
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

API = "https://api.telegram.org/bot{token}/{method}"


def api_call(token, method, payload):
    req = urllib.request.Request(
        API.format(token=token, method=method),
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=35) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        sys.exit(f"Telegram API error {e.code}: {e.read().decode('utf-8')}")
    except urllib.error.URLError as e:
        sys.exit(f"Could not reach Telegram API: {e.reason}")
    if not body.get("ok"):
        sys.exit(f"Telegram API rejected request: {body}")
    return body["result"]


def fmt_chat(chat):
    cid = chat["id"]
    ctype = chat.get("type", "?")
    title = chat.get("title")
    uname = chat.get("username")
    name = " ".join(x for x in (chat.get("first_name"), chat.get("last_name")) if x)
    ident = uname or name or title or ""
    return f"{cid} ({ctype}{f', {ident}' if ident else ''})"


def dump_update(update):
    u = update.get("update_id")
    m = update.get("message")
    if not m:
        return False
    mid = m.get("message_id")
    chat = fmt_chat(m.get("chat", {}))
    sender = m.get("from", {}).get("first_name", "?")
    text = m.get("text") or m.get("caption") or ""
    if "photo" in m:
        text = "[photo]"
    elif "video" in m:
        text = "[video]"
    text = (text[:80] + "…") if len(text) > 80 else text
    print(f"update_id={u}  message_id={mid}  chat_id={chat}")
    print(f"  sender={sender}  text={text!r}")
    print(f"<<< CHAT ID: {m['chat']['id']}")
    print()
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--token", help="bot token (or set TELEGRAM_BOT_TOKEN)")
    ap.add_argument("--repeat", "-r", action="store_true",
                    help="keep polling for new messages")
    ap.add_argument("--seconds", "-s", type=int, default=60,
                    help="total polling duration when --repeat (default 60)")
    args = ap.parse_args()

    token = args.token or os.environ.get("TELEGRAM_BOT_TOKEN")
    if not token:
        sys.exit("No token: pass --token or set TELEGRAM_BOT_TOKEN")

    if args.repeat:
        print(f"Polling for {args.seconds}s — message the bot to capture its chat ID.")

    # Telegram confirms an update once we pass offset = its id + 1 (in ascending
    # order). The first call omits offset so every pending update is dumped now;
    # later calls advance it, so re-running skips the same messages.
    offset = None
    deadline = time.monotonic() + args.seconds
    seen_any = False
    while True:
        payload = {"timeout": 30}
        if offset is not None:
            payload["offset"] = offset + 1
        for u in api_call(token, "getUpdates", payload):
            if dump_update(u):
                seen_any = True
                offset = u["update_id"]
        if not args.repeat:
            break
        if time.monotonic() >= deadline:
            print(f"--repeat done after {args.seconds}s.")
            return
    if not seen_any:
        print("No updates found. Start a chat with the bot (send /start), then run again.")
    elif not args.repeat:
        print("Done. Use --repeat (or message the bot again) for more.")


if __name__ == "__main__":
    main()