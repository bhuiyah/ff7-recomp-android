# game/

Put your own copy of FINAL FANTASY VII (Steam 2026 release, app 3837340) here. Nothing in this folder except this README is committed.

Expected layout, imported by `tools/import_game.py` from your Steam install:

```
game/
  ff7_en.exe        <- copied from <Steam FF7>\ff7\resources\ff7_1.02\ff7_en (32-bit 1.02 US exe)
  data/             <- the game's data folder (lgp archives, movies, music)
```

`tools/check_inputs.py` checks these files and records their SHA-1 hashes in `analysis/inputs.json`.
