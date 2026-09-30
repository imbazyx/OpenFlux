#!/usr/bin/env bash
# The Windows client has to show the same icon as the Android one, so it is
# built from the same file rather than redrawn: an icon that is derived cannot
# drift from the icon it is supposed to match, and a second hand-made copy would
# eventually not match.
#
# Produces OpenFluxPC/icon/openflux.ico, a build input the Gradle build reads.
# Run it only when the Android icon changes:
#     bash scripts/make-pc-icon.sh
#
# Needs rsvg-convert (apt install librsvg2-bin). The .ico is committed, so a
# normal build does not need any of this.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

RES=androidApp/src/main/res/drawable
BG=$RES/ic_openflux_background.xml
FG=$RES/ic_openflux_launcher_foreground.xml
OUT=OpenFluxPC/icon/openflux.ico

command -v rsvg-convert >/dev/null || {
  echo "нужен rsvg-convert: apt install librsvg2-bin" >&2; exit 2; }

# Pulls the data out of the Android drawables instead of restating it: a typo in
# a path is invisible until someone looks at the icon, and a changed icon in
# androidApp would silently not reach Windows.
python3 - "$BG" "$FG" > /tmp/openflux-icon.svg <<'PY'
import re, sys, xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"

# Android drawables mix namespaced attributes with unprefixed element names:
# <path android:pathData=...> is an element in no namespace at all. Matching
# elements by their namespaced tag silently finds nothing here - the file
# parses, the icon comes out empty, and an empty icon is still a valid one.
def local(el):
    return el.tag.split("}")[-1]

def elements(path, name):
    return [el for el in ET.parse(path).getroot().iter() if local(el) == name]

def viewport(path):
    root = ET.parse(path).getroot()
    return float(root.get(A + "viewportWidth")), float(root.get(A + "viewportHeight"))

def paths(path):
    """Every <path> in document order, as (pathData, fillRule)."""
    out = []
    for el in elements(path, "path"):
        d = el.get(A + "pathData")
        if d:
            out.append((d, el.get(A + "fillType") or "nonZero"))
    return out

def scale_of(path):
    for g in elements(path, "group"):
        sx = g.get(A + "scaleX"); sy = g.get(A + "scaleY")
        if sx and sy:
            return float(sx)
    return 1.0

w, h = viewport(sys.argv[1])
grad = next(el for el in elements(sys.argv[1], "gradient"))
x1, y1 = grad.get(A + "startX"), grad.get(A + "startY")
x2, y2 = grad.get(A + "endX"), grad.get(A + "endY")
c1, c2 = grad.get(A + "startColor"), grad.get(A + "endColor")

s = scale_of(sys.argv[2])
print(f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:.0f}" height="{h:.0f}" '
      f'viewBox="0 0 {w:.0f} {h:.0f}">')
# The gradient runs past the canvas on purpose, exactly as Android does.
print(f'  <defs><linearGradient id="bg" gradientUnits="userSpaceOnUse" '
      f'x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">'
      f'<stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/>'
      f'</linearGradient></defs>')
print(f'  <rect x="0" y="0" width="{w:.0f}" height="{h:.0f}" fill="url(#bg)"/>')
# Android scales the foreground about the centre; SVG does the same explicitly.
print(f'  <g transform="translate({w/2} {h/2}) scale({s}) translate({-w/2} {-h/2})">')
for d, rule in paths(sys.argv[2]):
    fr = 'evenodd' if rule == 'evenOdd' else 'nonzero'
    print(f'    <path fill="#FFFFFF" fill-rule="{fr}" d="{d}"/>')
print('  </g>')
print('</svg>')
PY

mkdir -p "$(dirname "$OUT")"
TMP=$(mktemp -d)
# The sizes Windows actually asks for: 16 for the title bar, 24 for the small
# taskbar, 32 for the taskbar, 48 for a medium shell icon, then up to 256 for
# large icons and the installer. A single 256 looks blurry everywhere else.
SIZES="16 24 32 48 64 128 256"
for s in $SIZES; do
  rsvg-convert -w "$s" -h "$s" /tmp/openflux-icon.svg -o "$TMP/$s.png"
done

python3 - "$OUT" "$TMP" $SIZES <<'PY'
import struct, sys, os
out, tmp, sizes = sys.argv[1], sys.argv[2], [int(x) for x in sys.argv[3:]]
imgs = []
for s in sizes:
    with open(os.path.join(tmp, f"{s}.png"), "rb") as f:
        imgs.append((s, f.read()))

# ICO: a directory, then one entry per image, then the images themselves. The
# 256 entry stores 0 in the width byte, because 256 does not fit in a byte.
hdr = struct.pack("<HHH", 0, 1, len(imgs))
offset = 6 + 16 * len(imgs)
entries, blobs = b"", b""
for s, data in imgs:
    dim = 0 if s >= 256 else s
    entries += struct.pack("<BBBBHHII", dim, dim, 0, 0, 1, 32, len(data), offset)
    blobs += data
    offset += len(data)

with open(out, "wb") as f:
    f.write(hdr + entries + blobs)
print(f"{out}: {len(imgs)} размеров, {os.path.getsize(out)} байт")
PY

rm -rf "$TMP"
echo "готово: $OUT"
