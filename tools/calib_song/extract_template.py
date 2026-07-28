#!/usr/bin/env python3
"""Regenerate template_blocks.py from a known-good, hardware-written song.

The calibration generator copies its structural skeleton verbatim from a song the Deluge itself
wrote, so that generated files load on hardware instead of being rejected as FILE_CORRUPTED. Run
this if the firmware's song format moves and the generated songs stop loading.

    python3 tools/calib_song/extract_template.py /path/to/card/SONGS/ALLSYN_1.XML
"""

import re
import sys


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    src = open(sys.argv[1], encoding="utf-8", errors="replace").read()

    m = re.search(r'<sound presetName="[^"]+"', src)
    if not m:
        sys.exit("no <sound> element found — is this a song file?")
    blk = src[m.start():src.find("</sound>", m.start())]

    out = {"SONG_OPEN": re.match(r"<song[^>]*>", src[src.find("<song"):]).group(0)}
    for tag in ["lfo1", "arpeggiator", "defaultParams", "modKnobs", "customLfoWave"]:
        mm = re.search(r"<%s[^>]*/>|<%s\b.*?</%s>" % (tag, tag, tag), blk, re.S)
        if not mm:
            sys.exit("missing <%s> in the template sound" % tag)
        out[tag.upper()] = mm.group(0)
    i = src.rfind("\t<sections>")
    if i < 0:
        sys.exit("no <sections> block found")
    out["TAIL"] = src[i:]

    with open("template_blocks.py", "w", encoding="utf-8") as f:
        f.write('"""Verbatim structural blocks lifted from a hardware-written ALLSYN song.\n\n'
                "These are copied unchanged so the generated calibration songs share the exact "
                "skeleton of a\nfile the Deluge itself wrote — the surest way to avoid "
                "FILE_CORRUPTED on load. Do not\nhand-edit; regenerate with "
                'tools/calib_song/extract_template.py against a known-good song.\n"""\n\n')
        for k in ["SONG_OPEN", "LFO1", "ARPEGGIATOR", "DEFAULTPARAMS", "MODKNOBS",
                  "CUSTOMLFOWAVE", "TAIL"]:
            f.write("%s = %r\n\n" % (k, out[k]))
    print("wrote template_blocks.py from %s" % sys.argv[1])


if __name__ == "__main__":
    main()
