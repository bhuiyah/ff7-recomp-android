"""Import the user's FF7 (Steam 2026 release) into game/.

Copies the 32-bit 1.02 exe (ff7\\resources\\ff7_1.02\\ff7_en) to game/ff7_en.exe and links
game/data to the install's ff7\\workingdir\\data (a directory junction on Windows, so the
3.5 GB of data is not duplicated). Nothing is modified in the install itself.

usage: py -I tools/import_game.py "<path to FINAL FANTASY VII install>"
"""
import json
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GAME = os.path.join(ROOT, 'game')


def main(install):
    install = os.path.abspath(install)
    exe = os.path.join(install, 'ff7', 'resources', 'ff7_1.02', 'ff7_en')
    data = os.path.join(install, 'ff7', 'workingdir', 'data')
    for p in (exe, data):
        if not os.path.exists(p):
            sys.exit(f'missing: {p}\nIs this the FINAL FANTASY VII (Steam 2026) install folder?')

    os.makedirs(GAME, exist_ok=True)
    shutil.copy2(exe, os.path.join(GAME, 'ff7_en.exe'))
    print('copied ff7_en -> game/ff7_en.exe')

    link = os.path.join(GAME, 'data')
    if os.path.lexists(link):
        if os.path.realpath(link) != os.path.realpath(data):
            sys.exit(f'game/data already exists and points elsewhere: {os.path.realpath(link)}')
        print('game/data already linked')
    elif os.name == 'nt':
        subprocess.run(['cmd', '/c', 'mklink', '/J', link, data], check=True, capture_output=True)
        print(f'linked game/data -> {data}')
    else:
        os.symlink(data, link)
        print(f'linked game/data -> {data}')

    with open(os.path.join(GAME, 'source.json'), 'w', encoding='utf-8') as f:
        json.dump({'install': install, 'release': 'steam-2026', 'steam_appid': 3837340}, f, indent=2)


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
