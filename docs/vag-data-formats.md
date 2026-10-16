# VAG data file formats

Notes on the data files Deep OBD stores under `Android/data/de.holeschak.bmw_deep_obd/files/Ecu/Vag/`,
as decoded by the `vagdata` module. They describe what was observed in a real download (2,262 `.ldat`,
1 `.cdat`, 7,829 `.uds` files) and in the open-source Deep OBD reader, not an official specification.

> The label content itself (Ross-Tech VCDS label files and VW-group data) is third-party material.
> Do not commit it to this repository; the app imports it from a folder the user points it at.

## Directory layout

```
Vag/<language>/            en (and others) – one directory per language
  codes.cdat               numeric code -> text
  labels/*.ldat            label files, named after part numbers (flat)
  uds_ev/*.uds             UDS ECU descriptions and shared tables
  uds_ev/chassis.ldat      a chassis table that uses the .ldat container
```

## Container: encrypted zip

Every `.ldat`, `.cdat` and `.uds` file is a zip archive containing **one entry with the same name as the file**
(case-insensitive). The entry is deflated and encrypted with traditional PKWARE encryption ("ZipCrypto").

- **Password:** `MD5(UPPERCASE(file name without extension))` as upper-case hex.
  `00-01.ldat` is opened with `MD5("00-01") = C140326DDA90FCA55F3803FD2FB03699`.
- Archives use a data descriptor (flag bit 3), so sizes must come from the central directory.
- **Text encoding:** Windows-1252, or Windows-1251 for names ending in `-rus` and files inside a `rus` directory.
  Line endings are CRLF. A few files contain bytes that are undefined in Windows-1252 (e.g. `0x81`).

## `codes.cdat`

`<unsigned 32-bit integer>,<text>` per line, text may contain commas. Observed: 20,822 lines, no duplicates, no blanks.

## `.ldat` label files

Text lines; everything from the first `;` to the end of the line is a comment (so label text cannot contain `;`).
Columns are split on every comma. The first column is the record tag:

| Tag | Shape | Meaning |
|---|---|---|
| `REDIRECT` | `REDIRECT,<file>.LBL,<pattern>[,HW\|SL]` | maps part numbers matching `<pattern>` to another label file |
| digits | `<group>,<index>,<text>…` | measuring block label (`index` 0 = title, 1–4 = fields); columns: name, unit, `Range:/Specification:` notes |
| `A<n>` | `A<channel>,<index>,<text>…` | adaptation channel |
| `B<n>` | `B<group>,<index>,<text>…` | basic setting |
| `L<n>` `S<n>` `C<n>` | `<tag>,<text>…` | numbered login / settings (security access) / coding help text |
| `LC` | `LC,<byte>,<bits>,[<value>,]<text>` | long coding: `<bits>` is a bit (`6`), a range (`0~7` or `0-7`, then a **hex** value) or a line number (`2=`) |
| other | `O`, `V`, `SN`, `W`, `RJ`, `8X,AU21`, `#"…"`, `: …` | metadata, vehicle lines and tables; meaning not decoded yet |

Texts may contain the two-character sequence `\n` (backslash, n) meaning a line break.

Observed record counts: 119,423 group labels, 15,569 long-coding, 14,178 redirects, 8,720 section labels, 5,973 other.

### Finding the label file for an ECU

Inputs: part number (`1K0035274B`), diagnostic address (`0x37`), optionally hardware and sub-system part numbers.
A part number of nine or more characters is written `1K0-035-274` plus `-B` for any suffix.

1. Look for `<1k0-035-274-b>.ldat`, then `<1k0-035-274>.ldat` (lower case, in `labels/` or its sub-directories).
2. Otherwise look for the **redirect file** `<first two characters>-<address as 2 hex digits>.ldat` (`1k-37.ldat`), with
   `-<index+1 as 2 hex digits>` appended when resolving a sub-system.
3. In a redirect file, take the first `REDIRECT` whose pattern matches the part number (or the hardware / sub-system
   one when the line ends in `HW` / `SL`) and open its target, with the extension changed to `.ldat` and lower-cased.
   `?` matches one character; trailing `?`s and the `-` before them are optional (prefix match).
4. A direct file may itself contain `REDIRECT` lines listing suffixes (`-B,-C`) that are appended to its base part number.

## `.uds` files

Segments of comma separated rows between `[NAME]` and `[/NAME]` lines; a segment can occur more than once in a file.

| Segment | Rows (all files) | Segment | Rows |
|---|---|---|---|
| `DTC` | 1,739,349 | `INC` | 17,261 |
| `MWB` | 1,802,792 | `FFMUX` | 11,295 |
| `ADP` | 256,970 | `SLV` | 3,678 |
| `SOT` | 70,668 | `CMP` | 7,826 |
| `XPL` | 40,551 | `IDN` | 85 |
| `GES` | 43,508 | | |

Shared tables use `TXT`, `MUX`, `DOP`, `UNT` and `DIR`. The columns' meaning (value types such as scaled floats,
multiplexed values, DTC detail records) is implemented in Deep OBD's `UdsReader.cs` and is **not** decoded here yet.

## Not covered yet

- Meaning of the `.uds` columns, which is needed for live data and fault codes on UDS control modules.
- The ODIS-style XML under `EcuVag/dat.ukd` and the `*.enc0` EDIABAS objects.
