"""Bundle a Windows app image with DocuFind's current compiled classes."""

from pathlib import Path
import ctypes
import hashlib
import re
import struct
import sys
import zipfile


app_image = Path(sys.argv[1]).resolve()
classes = Path(sys.argv[2]).resolve()
output_jar = Path("app.jar")

with zipfile.ZipFile(app_image / "app/docufind.jar") as previous:
    with zipfile.ZipFile(output_jar, "w", zipfile.ZIP_DEFLATED) as updated:
        for item in previous.infolist():
            if not item.filename.startswith("io/docufind/"):
                updated.writestr(item, previous.read(item))
        for compiled in sorted(classes.rglob("*.class")):
            updated.write(compiled, compiled.relative_to(classes).as_posix())

lz4 = ctypes.CDLL("liblz4.so.1")
lz4.LZ4_compressBound.argtypes = [ctypes.c_int]
lz4.LZ4_compressBound.restype = ctypes.c_int
lz4.LZ4_compress_HC.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int,
                                ctypes.c_int, ctypes.c_int]
lz4.LZ4_compress_HC.restype = ctypes.c_int

with Path("payload").open("wb") as output:
    for path in sorted(app_image.rglob("*")):
        relative = path.relative_to(app_image).as_posix()
        if relative == "README.md" or path.name == ".package" or relative in {
            "runtime/lib/ct.sym", "runtime/lib/jvm.lib", "runtime/lib/jawt.lib"
        }:
            continue

        name = relative.replace("/", "\\").encode("utf-16-le")
        stored_data = b""
        if path.is_dir():
            size = 0xFFFFFFFF
        else:
            original = output_jar.read_bytes() if relative == "app/docufind.jar" else path.read_bytes()
            if relative == "app/DocuFind.cfg":
                original = re.sub(rb"(?<=jpackage\.app-version=)[^\r\n]+", b"2.2.0", original)
            size = len(original)
            compressed = ctypes.create_string_buffer(lz4.LZ4_compressBound(size))
            compressed_size = lz4.LZ4_compress_HC(
                original, compressed, size, len(compressed), 9
            )
            if compressed_size <= 0:
                raise RuntimeError(f"Could not compress {relative}")
            stored_data = compressed.raw[:compressed_size] if compressed_size < size else original

        output.write(struct.pack("<III", len(name), size, len(stored_data)))
        output.write(name)
        output.write(stored_data)
    output.write(bytes(12))

digest = hashlib.sha256(Path("payload").read_bytes()).hexdigest()[:16]
Path("bundle.h").write_text(f'#define RELEASE L"DocuFind-2.2.0-{digest}"\n')
