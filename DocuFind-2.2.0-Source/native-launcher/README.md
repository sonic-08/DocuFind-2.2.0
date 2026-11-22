# Single-file executable

The delivered `DocuFind-2.2.0.exe` contains a Windows app image, a Java runtime,
and the compiled 2.2.0 application. `launch.c` extracts them into the user's
`LOCALAPPDATA` directory, then starts the included Windows launcher.

`build-single.sh` reproduces this packaging on Linux. It needs Maven, Python 3,
GCC, GNU binutils with PE support, liblz4, MinGW import libraries, and a Windows
DocuFind portable app image. Run it from the project folder with the app image
directory as its argument. The script runs the Java tests first. Packaging uses
high-compression LZ4 blocks and omits Java compiler-only runtime files.

The GitHub Actions workflow builds a separate Windows installer directly from
the 2.2.0 Java sources. It uses a smaller Java runtime built with jlink. Its
installer is a different executable format.
