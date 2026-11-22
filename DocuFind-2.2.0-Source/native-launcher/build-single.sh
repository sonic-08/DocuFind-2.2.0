#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/Windows-app-image/DocuFind" >&2
    exit 2
fi

app_image=$(realpath "$1")
source_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$source_root/native-launcher"

mvn -B -f "$source_root/docufind/pom.xml" clean verify
python3 pack.py "$app_image" "$source_root/docufind/target/classes"

gcc -mabi=ms -fshort-wchar -Os -ffreestanding -fno-builtin -fpie \
    -fno-ident -fno-stack-protector -fno-asynchronous-unwind-tables \
    -c launch.c -o launch.o
as --64 data.s -o data.o

mingw_lib_dir=${MINGW_LIB_DIR:-/usr/x86_64-w64-mingw32/sys-root/mingw/lib}
ld -mi386pep --subsystem windows -e entry --stack 0x200000 -s \
    -o "$source_root/DocuFind-2.2.0.exe" launch.o data.o \
    -L"$mingw_lib_dir" -lkernel32 -lshell32 -luser32
