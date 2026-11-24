import pathlib
import struct
import sys
import zipfile


def verify(read_library):
    host = read_library("libMNN_htpops.so")
    if host[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", host, 16) != (3, 183):
        sys.exit("Invalid arm64 FastRPC host library")
    if b"Java_com_localai_notif_LlmEngine_nativeHexagonArch\0" not in host:
        sys.exit("FastRPC host library lacks the device architecture probe")
    for version in ("73", "75", "79", "81", "83", "85"):
        name = f"libMNN_htpops_skelV{version}.so"
        data = read_library(name)
        if (len(data) < 52 or data[:6] != b"\x7fELF\x01\x01"
                or struct.unpack_from("<HH", data, 16) != (3, 164)
                or struct.unpack_from("<I", data, 36)[0] & 0xff != int(version, 16)):
            sys.exit(f"{name} is not a Hexagon v{version} shared library")
        print(f"Verified Hexagon v{version}: {len(data)} bytes")


if __name__ == "__main__":
    path = pathlib.Path(sys.argv[1])
    if path.is_dir():
        verify(lambda name: (path / name).read_bytes())
    else:
        with zipfile.ZipFile(path) as archive:
            verify(lambda name: archive.read(f"lib/arm64-v8a/{name}"))
