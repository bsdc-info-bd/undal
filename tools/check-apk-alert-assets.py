#!/usr/bin/env python3
"""Prove the order alert's own assets are inside the APK that is about to ship.

The alert ladder has gates for the code that rings, and the readiness screen
proves the phone is armed. Neither of them answers the last question, which is
the one the owner asked first — *"no sound"* — and which no amount of Kotlin can
answer from a repository:

    is the sound that the ringing code plays actually inside the signed APK?

An `R.raw` reference that no packaging step ever resolved, a shrinking config
that dropped the tone, or a placeholder file committed in its place all produce
a phone that rings with nothing audible, and every one of them builds green.

This check runs on the finished, signed artefact and fails the build if:

1. the order tone is not in `res/raw` — the file the ring code names;
2. it is smaller than `MIN_ORDER_TONE_BYTES`, which the 47-second tone this
   repository ships clears many times over, so a truncated or placeholder file
   cannot pass;
3. the preparation tick is missing;
4. the customer channel id is not in the resource table, which is the channel
   the platform is told to use for a notification the app did not build.

Usage:  python3 tools/check-apk-alert-assets.py undal.apk
Exit:   0 when the alert can be heard from this artefact, 1 otherwise.
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

# The shipped tone is ~1.1 MB (47.8 s at 192 kbps). Half a megabyte still leaves
# room for a future re-encode while making an empty or placeholder file fail.
MIN_ORDER_TONE_BYTES = 500_000

ORDER_TONE_PREFIX = "res/raw/undal_order_alert"
PREP_TICK = "res/raw/undal_prep_tick.wav"
CUSTOMER_CHANNEL = "channel_customer_orders"


def fail(problems: list[str]) -> int:
    print("check-apk-alert-assets: the order alert cannot be heard from this APK\n")
    for problem in problems:
        print(f"  ✗ {problem}")
    print("\nRESULT: a kitchen installing this build would hear nothing.\n")
    return 1


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__.strip())
        return 2

    apk = Path(argv[1])
    if not apk.is_file():
        return fail([f"{apk} does not exist, so nothing was verified"])

    problems: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        entries = {info.filename: info.file_size for info in archive.infolist()}

        tones = sorted(name for name in entries if name.startswith(ORDER_TONE_PREFIX))
        if not tones:
            problems.append(
                f"{ORDER_TONE_PREFIX}.* is not in the APK — the ringing code plays "
                "R.raw.undal_order_alert and there is nothing there to play"
            )
        else:
            tone = tones[0]
            size = entries[tone]
            print(f"  · order tone: {tone} ({size:,} bytes)")
            if size < MIN_ORDER_TONE_BYTES:
                problems.append(
                    f"{tone} is only {size:,} bytes, below the {MIN_ORDER_TONE_BYTES:,}-byte floor — "
                    "this is a placeholder, not the order sound"
                )

        if PREP_TICK not in entries:
            problems.append(f"{PREP_TICK} is missing: the prep countdown would be silent too")
        else:
            print(f"  · prep tick: {PREP_TICK} ({entries[PREP_TICK]:,} bytes)")

        # The channel id is a resource, so it lives in the resource table. Its
        # presence is what makes `default_notification_channel_id` resolvable for
        # a notification the platform builds while the app is closed.
        table = archive.read("resources.arsc") if "resources.arsc" in entries else b""
        if CUSTOMER_CHANNEL.encode("utf-16-le") not in table and CUSTOMER_CHANNEL.encode() not in table:
            problems.append(
                f"{CUSTOMER_CHANNEL} is not in resources.arsc — a background notification "
                "would land on a channel that does not exist"
            )
        else:
            print(f"  · customer channel: {CUSTOMER_CHANNEL} is in resources.arsc")

    if problems:
        return fail(problems)

    print(f"\nRESULT: {apk.name} carries the order sound, the tick and the channels.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
