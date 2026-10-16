# ECU-stuff – project context

Read this first. It summarises the decisions and findings from the sessions that started the project, so a
new (local or cloud) session does not have to rediscover them. `README.md` describes the code; this file
describes the *why* and what comes next.

## Goal
A **cleaner Android app for VAG diagnostics** (Kotlin) that talks to the owner's **ELM327 adapter running the
Deep OBD replacement firmware** (PIC18F25K80). The owner finds the Deep OBD app itself hard to use; its protocol
and adapter work is good, its UI is not. Chosen approach: **fresh app with our own protocol stack**, using
Deep OBD's open-source code only as a *specification* (reading `main.c` and the telegram code to learn the wire
format). Porting its code would make this project a GPLv3 derivative; independent reimplementation avoids that.

## Upstream reference (read-only)
`https://github.com/uholeschak/ediabaslib` (GPLv3, Ulrich Holeschak). There is no repo called "deepobd"; the
Deep OBD Android app lives inside it. Shallow-clone it somewhere *outside* this repo when you need it.

| Path in ediabaslib | What it tells us |
|---|---|
| `EdiabasLib/CanAdapterElm/CanAdapterElm.X/main.c`, `can.c` | adapter firmware source (C, ~4,300 lines): the wire protocol |
| `EdiabasLib/EdiabasLib/EdCustomAdapterCommon.cs` | builds the adapter telegrams: `0x00` header, type, baud/2, flags, length, payload, checksum; K-Line and CAN telegram variants; protocols BMW / TP2.0 / ISO-TP; optional escape mode |
| `EdiabasLib/EdiabasLib/EdInterfaceObd.cs` | protocol logic (KWP2000, KWP1281, TP2.0 …), ~5,900 lines |
| `EdiabasLib/UdsFileReader/DataReader.cs`, `UdsReader.cs` | how the label/UDS files are read and interpreted |
| `BmwDeepObd/VagCodingActivity.cs`, `VagAdaptionActivity.cs` | VAG coding / adaption UI and flow |
| `docs/AdapterTypes.md`, `docs/Replacement_firmware_for_ELM327.md` | supported adapters, firmware notes |

Facts established from that code (not yet re-verified on hardware):
- The adapter does the timing-critical work (K-Line fast init and pulses, KWP1281 detection, TP2.0 keep-alive,
  ISO-TP), so the phone side mostly builds framed telegrams and parses replies.
- VAG protocols: KWP2000, KWP1281, TP2.0. Docs disagree on range: README says cars until 04/2012, the app doc
  says vehicles until 2017-08. UDS ECUs are handled via ISO-TP and the `.uds` files.
- Deep OBD can record adapter traffic: *Data logging → Create trace file* writes `ifh.trc` to
  `Android/data/de.holeschak.bmw_deep_obd/files/Log/`. **These traces are the ground truth for step 2.**
- Classic Bluetooth SPP cannot be used from iOS, so the app is Android-only unless the adapter is WiFi or BLE.

## Plan
| Step | What | State |
|---|---|---|
| 1 | `vagdata`: read the label files Deep OBD downloads | **done** |
| 2 | Adapter telegram layer, tested by replaying recorded `ifh.trc` traces (no hardware needed) | not started – needs a trace |
| 3 | KWP2000 / TP2.0 fault codes and live data, then the UI | not started |

Also open: the meaning of the `.uds` columns (see `UdsReader.cs`), needed for live data on UDS modules; and the
tags `O`, `V`, `SN`, `W`, `RJ` in `.ldat` files, which are kept but not decoded.

### Still needed from the owner
- Car make / model / year (decides the protocol: KWP1281, KWP2000, TP2.0 or UDS)
- Adapter transport: Bluetooth classic, BLE or WiFi
- Confirmation that Android-only Kotlin is fine (assumed default)
- Scope: fault codes + live data first (assumed), or also coding / adaption
- A recorded `ifh.trc` from a real scan

## Third-party data – hard rule
The label files are **Ross-Tech VCDS label files** (their copyright header is in the files) plus VW-group
ODIS-style data, repackaged by Deep OBD. **Never commit them to this repo** (`.gitignore` blocks `data/`,
`*.ldat`, `*.cdat`, `*.uds`, `*.enc0` except under `vagdata/src/test`). The app must import them from a folder
the user chooses and never bundle them. Tests use small **invented** fixtures
(`scripts/make-test-fixtures.sh`).

The owner's copy (883 MiB, 17,916 files) sits on the private branch **`labels-upload`**: a data stash. Do not merge
it. Layout: `data/ecu/Vag/<lang>/{codes.cdat,labels/,uds_ev/}` and `data/ecu/EcuVag/` (ODIS-style XML under
`dat.ukd/` and 487 `*.enc0` EDIABAS objects, not needed for the current plan). On the phone it is `/sdcard/ecu`;
the app's own copy lives under `Android/data/de.holeschak.bmw_deep_obd/files/Ecu/Vag/`.

## `vagdata` status
Formats are in `docs/vag-data-formats.md`. Verified against the full real download: all 2,262 `.ldat` and 7,829
`.uds` files decrypt and parse, `codes.cdat` has 20,822 entries with none rejected, all 15,569 long-coding lines
parse. The label resolver is verified against one real part number only (`1K0035274B` at `0x37`).
Decisions worth knowing: `;` starts a comment anywhere in a line (as in the original app); long-coding bit
ranges accept both `0~7` and `0-7` (the original only understands `~`, per reading its code); unknown lines
become `OtherRecord`, nothing is dropped.

## Working here
- Build / test: `./gradlew :vagdata:test` (JDK 17+). Real data: `ECU_VAG_DIR=<…>/Vag/en ./gradlew
  :vagdata:cleanTest :vagdata:test --tests '*RealDataTest*' -i` (skipped when unset).
- Warnings are errors in the Kotlin build.
- The cloud sandbox rate-limits Maven Central; builds there need a Gradle init script that points at Google's
  Maven Central mirror (`maven-central.storage-download.googleapis.com`). It lives in `~/.gradle/init.d/`, never in
  the repo. On a normal machine nothing special is needed.
- Develop on the branch you were given; open a pull request only when asked. Do not put model identifiers in
  commit messages.
- The owner mostly works from an Android phone (Termux for uploads) and likes light humour in replies.
- Android now has "developer verification" for sideloaded apps (reported start: Sept 2026 in Brazil, Indonesia,
  Singapore, Thailand; rest of world 2027; free hobbyist tier up to 20 devices). Details were not confirmed
  against Google's current pages: check before relying on them.
