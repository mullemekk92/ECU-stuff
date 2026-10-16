#!/usr/bin/env bash
# Regenerates vagdata/src/test/resources from vagdata/src/test/fixtures-src using Info-ZIP's `zip`,
# i.e. an implementation independent of the Kotlin decryptor under test. Everything here is invented
# test data; no third-party label content is used.
set -euo pipefail
cd "$(dirname "$0")/../vagdata/src/test"
SRC=fixtures-src
OUT=resources
rm -rf "$OUT" && mkdir -p "$OUT/zip" "$OUT/data/en/labels" "$OUT/data/en/uds_ev"

# Password the label files use: upper-case hex MD5 of the upper-cased base name.
pw_for() { local b="${1##*/}"; b="${b%.*}"; printf '%s' "${b^^}" | md5sum | cut -d' ' -f1 | tr 'a-f' 'A-F'; }

# Zip a file the way the app ships it: single entry with the same name, password from its name.
pack() { # <source file> <dest dir>
  local name; name="$(basename "$1")"
  local tmp; tmp="$(mktemp -d)"
  cp "$1" "$tmp/$name"
  ( cd "$tmp" && zip -q -j -P "$(pw_for "$name")" "$OLDPWD/$2/$name.zip" "$name" ) 
  mv "$2/$name.zip" "$2/$name"
  rm -rf "$tmp"
}

# --- generic zip fixtures, password "secret" ---
cp "$SRC/zip/hello.txt" "$OUT/zip/hello.txt"
( cd "$SRC/zip" && zip -q -P secret "$OLDPWD/$OUT/zip/file-input.zip" hello.txt )               # archived from a file
( cd "$SRC/zip" && zip -q -P secret "$OLDPWD/$OUT/zip/stdin-input.zip" - < hello.txt )         # archived from stdin (entry name is "-")
( cd "$SRC/zip" && zip -q -0 -P secret "$OLDPWD/$OUT/zip/stored.zip" hello.txt )              # stored, not deflated
( cd "$SRC/zip" && zip -q "$OLDPWD/$OUT/zip/plain.zip" hello.txt )                            # not encrypted at all

# --- label-style fixtures ---
for f in "$SRC"/data/en/codes.cdat "$SRC"/data/en/labels/*.ldat; do
  dest="$OUT/data/en"; [[ "$f" == */labels/* ]] && dest="$OUT/data/en/labels"
  pack "$f" "$dest"
done
pack "$SRC/data/en/uds_ev/sample.uds" "$OUT/data/en/uds_ev"

# --- encoding fixtures: Windows-1252 (incl. the undefined byte 0x81) and Windows-1251 ---
tmp="$(mktemp -d)"
printf 'LC,00,0,Gr\xf6\xdfe \x81 end\r\n' > "$tmp/zz9-000-001.ldat"                      # "Größe", then an undefined byte
printf 'LC,00,0,\xcf\xf0\xe8\xe2\xe5\xf2\r\n' > "$tmp/zz9-000-002-rus.ldat"               # "Привет" in cp1251
pack "$tmp/zz9-000-001.ldat" "$OUT/data/en/labels"
pack "$tmp/zz9-000-002-rus.ldat" "$OUT/data/en/labels"
rm -rf "$tmp"
echo "fixtures written to $OUT"
