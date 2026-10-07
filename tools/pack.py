# MY MUSIC build helper: puts the app file together.
#   python3 tools/pack.py base.apk classes.dex unsigned.apk [LIBDIR]
# LIBDIR: the built-in downloader's lib*.so files -> lib/arm64-v8a/
# base.apk comes from aapt2 (manifest, resources, the page). Android 11+
# wants resources.arsc stored as-is (not compressed) and starting on a
# 4-byte boundary, so this writes a fresh zip with that done.
import sys
import zipfile

import os
base, dex, out = sys.argv[1:4]
libdir = sys.argv[4] if len(sys.argv) > 4 else ""
STAMP = (2026, 10, 5, 0, 0, 0)
STORE = {"resources.arsc"}

with zipfile.ZipFile(base) as zin:
    items = [(i.filename, zin.read(i.filename)) for i in zin.infolist()
             if not i.filename.endswith("/") and i.filename != "classes.dex"]
first = [x for x in items if x[0] == "AndroidManifest.xml"]
rest = [x for x in items if x[0] != "AndroidManifest.xml"]
with open(dex, "rb") as fh:
    items = first + [("classes.dex", fh.read())] + rest
if libdir and os.path.isdir(libdir):
    for n in sorted(os.listdir(libdir)):
        if n.startswith("lib") and n.endswith(".so"):
            with open(os.path.join(libdir, n), "rb") as fh:
                items.append(("lib/arm64-v8a/" + n, fh.read()))

with open(out, "wb") as raw:
    z = zipfile.ZipFile(raw, "w")
    for name, data in items:
        zi = zipfile.ZipInfo(name, STAMP)
        zi.external_attr = 0o644 << 16
        if name in STORE:
            zi.compress_type = zipfile.ZIP_STORED
            start = raw.tell() + 30 + len(name.encode("utf-8"))
            zi.extra = b"\0" * ((-start) % 4)
        else:
            zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, data)
    z.close()

# check: every stored entry really starts on a 4-byte boundary
with open(out, "rb") as fh:
    blob = fh.read()
with zipfile.ZipFile(out) as zc:
    for i in zc.infolist():
        if i.compress_type == zipfile.ZIP_STORED:
            h = i.header_offset
            n = int.from_bytes(blob[h + 26:h + 28], "little")
            e = int.from_bytes(blob[h + 28:h + 30], "little")
            if (h + 30 + n + e) % 4:
                sys.exit("  STOPPED - %s is not aligned" % i.filename)
print("  packed: %d files" % len(items))
