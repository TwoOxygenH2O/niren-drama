#!/usr/bin/env python3
"""Create an isolated static subtitle font from the installed Noto variable font."""

import argparse
from pathlib import Path

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    font = TTFont(args.source)
    if 'fvar' not in font or not any(a.axisTag == 'wght' for a in font['fvar'].axes):
        raise ValueError('Expected a variable font with a weight axis')
    font = instantiateVariableFont(font, {'wght': 500}, inplace=True)
    # A distinct family prevents DirectWrite from choosing the installed Thin face.
    names = {1: 'Niren Subtitle Sans', 2: 'Regular', 3: 'NirenSubtitleSans-Medium-1',
             4: 'Niren Subtitle Sans Medium', 6: 'NirenSubtitleSans-Medium',
             16: 'Niren Subtitle Sans', 17: 'Regular'}
    for record in font['name'].names:
        if record.nameID in names:
            record.string = names[record.nameID].encode(record.getEncoding())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    font.save(args.output)
    notices = [n.toUnicode() for n in font['name'].names if n.nameID in (0, 13, 14)]
    args.output.with_suffix('.license.txt').write_text('\n\n'.join(dict.fromkeys(notices)), encoding='utf-8')
    print('Static subtitle font prepared: ' + str(args.output))


if __name__ == '__main__':
    main()
