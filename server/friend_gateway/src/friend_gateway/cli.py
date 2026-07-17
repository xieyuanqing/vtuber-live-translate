from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone

from .config import Settings
from .store import GatewayStore


def _time(value: int | None) -> str:
    if value is None:
        return "-"
    return datetime.fromtimestamp(value, tz=timezone.utc).isoformat(timespec="seconds")


def main() -> None:
    parser = argparse.ArgumentParser(description="Manage LiveTranslate friend invites")
    sub = parser.add_subparsers(dest="command", required=True)

    create = sub.add_parser("create", help="create invitation codes")
    create.add_argument("--count", type=int, default=1)
    create.add_argument("--label", default="friend-test")
    create.add_argument("--expires-days", type=int, default=30)

    sub.add_parser("list", help="list invitation metadata (never prints full codes)")

    revoke = sub.add_parser("revoke", help="revoke an invitation and its token")
    revoke.add_argument("code")

    reset = sub.add_parser("reset", help="clear the device binding for an invitation")
    reset.add_argument("code")

    args = parser.parse_args()
    store = GatewayStore(Settings.from_env())
    if args.command == "create":
        codes = store.create_invites(args.count, args.label, args.expires_days)
        print("Invitation codes are shown once. Store them securely:")
        for code in codes:
            print(code)
    elif args.command == "list":
        rows = store.list_invites()
        for row in rows:
            row["created_at"] = _time(row["created_at"])
            row["expires_at"] = _time(row["expires_at"])
            row["bound_at"] = _time(row["bound_at"])
            row["token_expires_at"] = _time(row["token_expires_at"])
            row["revoked_at"] = _time(row["revoked_at"])
        print(json.dumps(rows, ensure_ascii=False, indent=2))
    elif args.command == "revoke":
        if not store.revoke(args.code):
            raise SystemExit("invite not found or already revoked")
        print("revoked")
    elif args.command == "reset":
        if not store.reset(args.code):
            raise SystemExit("invite not found")
        print("reset")


if __name__ == "__main__":
    main()
