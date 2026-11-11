"""Build test mods for tests/mods/test_mods.cpp (into the directory given):

  test.iro      an IRO archive (version 0x10002) with raw, LZS and LZMA entries and a mod.xml
                using a ConfigOption, a ModFolder with ActiveWhen and a Conditional folder
  folder_mod/   a folder mod overriding one of the same files (priority tests)
  profile_default.xml / profile_alt.xml   profiles choosing the option's two values

LZMA comes from Python's own encoder, so the reader is checked against an independent
implementation. usage: py -I tests/mods/make_test_mods.py <outdir>
"""
import lzma
import os
import struct
import sys

MOD_XML = """<?xml version="1.0" encoding="utf-8"?>
<ModInfo>
  <ID>00000000-0000-0000-0000-000000000001</ID>
  <Name>Test Mod</Name>
  <Author>FF7Recomp</Author>
  <Version>1.5</Version>
  <ConfigOption>
    <Type>List</Type><Default>0</Default><ID>Style</ID><Name>Style</Name>
    <Option Value="0" Name="Classic"/><Option Value="1" Name="Alternative"/>
  </ConfigOption>
  <Variable Name="Custom">Int:0xDC1000:0xFF</Variable>
  <Conditional Folder="night"><RuntimeVar Var="Time" Values="1..2"/></Conditional>
  <Conditional Folder="perfile"><RuntimeVar ApplyTo="field\\perfile.bin" Var="FieldID" Values="116,117"/></Conditional>
  <Conditional Folder="custom"><Not><RuntimeVar Var="Custom" Values="5"/></Not></Conditional>
  <ModFolder Folder="alt" ActiveWhen="Style = 1"/>
  <ModFolder Folder="base"/>
  <Conditional Folder="cond"><ActiveWhen><Not><Option>Style = 1</Option></Not></ActiveWhen></Conditional>
  <LoadLibrary>plugin.dll</LoadLibrary>
</ModInfo>
"""

LZMA_TEXT = b"LZMA payload " * 200


def lzs_abcabc():
    # literals A B C, then a 6-byte match from ring position 0xFEE (where 'A' went)
    return bytes([0x07, ord('A'), ord('B'), ord('C'), 0xEE, 0xF3])


def lzma_entry(data):
    props_byte = (2 * 5 + 0) * 9 + 3                     # pb=2, lp=0, lc=3
    dict_size = 1 << 16
    filt = [{"id": lzma.FILTER_LZMA1, "lc": 3, "lp": 0, "pb": 2, "dict_size": dict_size}]
    raw = lzma.compress(data, format=lzma.FORMAT_RAW, filters=filt)
    props = bytes([props_byte]) + struct.pack("<I", dict_size)
    return struct.pack("<ii", len(data), len(props)) + props + raw


def write_iro(path, files):
    """files: [(name, flags, stored bytes)] -> IRO 0x10002 (offsets are int64)."""
    data = bytearray(b"\0" * 16)
    entries = []
    for name, flags, blob in files:
        entries.append((name, flags, len(data), len(blob)))
        data += blob
    directory = len(data)
    data += struct.pack("<i", len(entries))
    for name, flags, off, length in entries:
        fn = name.encode("utf-16-le")
        rec = struct.pack("<HH", len(fn) + 4 + 16, len(fn)) + fn + struct.pack("<iqi", flags, off, length)
        data += rec
    data[0:16] = struct.pack("<iiii", 0x534F5249, 0x10002, 0, directory)
    open(path, "wb").write(data)


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    write_iro(os.path.join(out, "test.iro"), [
        ("mod.xml", 0, MOD_XML.encode("utf-8")),
        ("base\\field\\raw.bin", 0, b"raw contents"),
        ("base\\field\\abc.bin", 1, lzs_abcabc()),
        ("base\\kernel\\big.bin", 2, lzma_entry(LZMA_TEXT)),
        ("base\\Textures\\shared.png", 0, b"from iro base"),
        ("alt\\field\\raw.bin", 0, b"alt contents"),
        ("cond\\field\\cond.bin", 0, b"conditional contents"),
        ("ignored\\x.bin", 0, b"not in any active folder"),
        ("night\\field\\raw.bin", 0, b"night contents"),
        ("perfile\\field\\perfile.bin", 0, b"perfile contents"),
        ("custom\\field\\custom.bin", 0, b"custom contents"),
        # hext: the root always counts for hext (not for files), active folders do, inactive not
        ("hext\\shared.txt", 0, b"root shared\r\n1000=01\r\n"),
        ("base\\hext\\shared.txt", 0, b"base shared\n1000=02\n"),
        ("night\\hext\\n.txt", 0, b"night\n1001=AA\n"),
        ("alt\\hext\\alt.txt", 0, b"alt only\n1002=BB\n"),
    ])
    open(os.path.join(out, "7thHeaven.var"), "w").write("Time=Byte:0xDC08EB\nFieldID=Short:0xCC15D0\n")
    fm = os.path.join(out, "folder_mod", "Textures")
    os.makedirs(fm, exist_ok=True)
    open(os.path.join(fm, "Shared.png"), "wb").write(b"from folder mod")
    os.makedirs(os.path.join(out, "folder_mod", "hext"), exist_ok=True)
    open(os.path.join(out, "folder_mod", "hext", "F.txt"), "wb").write(b"folder mod\n1000=03\n")
    for name, extra in (("profile_default.xml", ""), ("profile_alt.xml", '<Option ID="style" Value="1"/>')):
        open(os.path.join(out, name), "w").write(
            '<Profile Language="en">\n'
            f'  <Mod Path="folder_mod"/>\n'
            f'  <Mod Path="test.iro">{extra}</Mod>\n'
            '</Profile>\n')
    # <OrderConstraints><Before>: "theme_mod" must be above "base_ui" (Finishing Touch over
    # Enhanced Stock UI), so the profile listing base_ui first is corrected and theme_mod's hext
    # goes last (comments and stray text between the entries, as in Finishing Touch's mod.xml)
    # and <Compatibility><Setting>: while theme_mod's option t is 1 (its default), base_ui's
    # option view must be 5, which activates base_ui's folder v5 (Forbid 3 alone would pick 1)
    view = ('<ConfigOption><Type>List</Type><Default>3</Default><ID>view</ID><Name>View</Name>'
            '<Option Value="1" Name="One"/><Option Value="3" Name="Three"/><Option Value="5" Name="Five"/></ConfigOption>'
            '<ModFolder Folder="v5" ActiveWhen="view = 5"/>')
    os.makedirs(os.path.join(out, "base_ui", "v5", "hext"), exist_ok=True)
    open(os.path.join(out, "base_ui", "v5", "hext", "v.txt"), "wb").write(b"view 5\n2002=55\n")
    # ffnx_<key> conditions read <mods>/../game/FFNx.toml; a key it lacks counts as 0
    view += ('<ModFolder Folder="ws"><ActiveWhen><And><Option>ffnx_aspect_ratio = 2</Option>'
             '<Option>ffnx_not_there = 0</Option></And></ActiveWhen></ModFolder>'
             '<ModFolder Folder="w43"><ActiveWhen><Not><Option>ffnx_aspect_ratio = 2</Option></Not></ActiveWhen></ModFolder>')
    for f, b in (("ws", b"ws\n2003=66\n"), ("w43", b"43\n2004=77\n")):
        os.makedirs(os.path.join(out, "base_ui", f, "hext"), exist_ok=True)
        open(os.path.join(out, "base_ui", f, "hext", "w.txt"), "wb").write(b)
    os.makedirs(os.path.join(out, "..", "game"), exist_ok=True)
    open(os.path.join(out, "..", "game", "FFNx.toml"), "w").write(
        '# FFNx\n[section]\naspect_ratio = 2 # widescreen\nrenderer_backend = 5\nmod_path = "mods/Textures"\nfullscreen = true\n')
    for name, ident, compat, hext in (
            ("base_ui", "aaaaaaaa-0000-0000-0000-000000000001", view, b"base ui\n2000=11 22\n"),
            ("theme_mod", "bbbbbbbb-0000-0000-0000-000000000002",
             "<OrderConstraints> ==ui== <Before>AAAAAAAA-0000-0000-0000-000000000001</Before>"
             "<!--<Before>cccccccc-0000-0000-0000-000000000003</Before>-->"
             "<After>dddddddd-0000-0000-0000-000000000004</After></OrderConstraints>"
             "<ConfigOption><Type>Bool</Type><Default>1</Default><ID>t</ID><Name>Theme</Name></ConfigOption>"
             "<Compatibility><Setting><MyID>T</MyID><MyValue>1</MyValue>"
             "<ModID>aaaaaaaa-0000-0000-0000-000000000001</ModID><TheirID>View</TheirID><Require>5</Require></Setting>"
             "<Setting><MyID>t</MyID><MyValue>0</MyValue>"
             "<ModID>aaaaaaaa-0000-0000-0000-000000000001</ModID><TheirID>view</TheirID><Require>1</Require></Setting>"
             "</Compatibility>", b"theme\n2000=33\n")):
        os.makedirs(os.path.join(out, name, "hext"), exist_ok=True)
        open(os.path.join(out, name, "mod.xml"), "w").write(
            f"<ModInfo><ID>{ident}</ID><Name>{name}</Name>{compat}</ModInfo>\n")
        open(os.path.join(out, name, "hext", "p.txt"), "wb").write(hext)
    open(os.path.join(out, "profile_compat.xml"), "w").write(
        '<Profile Language="en">\n  <Mod Path="base_ui"/>\n  <Mod Path="theme_mod"/>\n</Profile>\n')
    open(os.path.join(out, "lzma_expected.bin"), "wb").write(LZMA_TEXT)


if __name__ == "__main__":
    main()
