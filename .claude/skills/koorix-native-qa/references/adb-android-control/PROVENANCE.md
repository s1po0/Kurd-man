# Reviewed direct-Android guidance

The owner supplied the `github.com/topics/claude-skill` discovery index as well
as `alirezarezvani/claude-skills`. Searching the topic's Android/ADB-related
repositories identified:

- Source: https://github.com/hah23255/adb-android-control
- Pinned commit: `cc1cb29de0adc581e260842b36c198217bfab0b2`
- License: MIT, copyright 2025 ADB Android Control Contributors; full license
  and unchanged original `SKILL.md` are retained here.
- Reviewed: original Skill, package metadata/license, relevant controller
  code, and available automation interfaces. Not an exhaustive code audit.
- Not installed or executed: upstream Termux setup, reconnection services,
  port scanners, CLI, device discovery, installers, root/data extraction,
  uninstall/clear-data, backup/restore, or its Python package.

The useful native operations are **real screencap, current UI hierarchy,
launch, tap, swipe and Back**, followed by another actual observation. The
Skill requires ADB plus an Android device/emulator; it cannot create missing
KVM/SDK hardware or access a reader's phone. Generic destructive examples in
the original are NOT permission to run them for Koorix.

`tools/direct-apk/` applies those public-UI operations through the repository's
existing official hosted Android34 emulator setup, with a small bounded
observe-act-observe mailbox. It opens the exact already-delivered production
1.8.2 APK, not a debug/alternate application. Every next gesture is selected
from the downloaded real frame and bound to that frame's digest. There is no
prewritten complete click-through test standing in for this direct session.
Only a fixed authored synthetic source is present; no reader pages, account
sessions, signing keys, Telegram credentials, app-private files, or public ADB
network listener are used. No APK build/release or delivery occurs.

The alirezarezvani pin remains
`08cd8a9fc86cc370e2c0d448e8171fbcfdbc5a30`. Reviewing the names and contents of
its distinct Skill documents did not identify an Android runtime/controller;
its rendered-build/review guidance is still used. The topic as a whole is not
claimed to have been exhaustively source-audited.
