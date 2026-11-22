# DocuFind Explorer 2.2.0

DocuFind is a Windows file explorer that automatically indexes accessible drives
and searches filenames and paths offline. Browse folders as soon as the app
opens. Search results appear as the initial scan progresses.

## Build the Windows installer with GitHub Actions

The source archive contains a `docufind` project folder and a
`.github/workflows/build-windows.yml` workflow. Place both at the root of your
repository, replacing your existing workflow at that path. Commit the changes
to the default branch, then:

1. Open **Actions → Build DocuFind for Windows → Run workflow**.
2. When the run succeeds, download **DocuFind-Windows-Installer** from its
   **Artifacts** section and extract it.
3. Run **DocuFind-2.2.0.exe** and then open **DocuFind** from the Start menu
   or desktop shortcut. The installer includes a Java runtime.

To install under Wine, use the same prefix for the installer and for DocuFind.
From the folder containing the extracted installer:

```bash
WINEPREFIX="$HOME/windows-system/Windows-testing/wine-env" wine ./DocuFind-2.2.0.exe
```

Open the new DocuFind shortcut after installation. An executable taken from the
portable application image requires its adjacent `app` and `runtime` folders.

## Browse and search

- Choose **This PC**, **Home folder**, or a drive in the sidebar. Use the address
  bar to go directly to a full path.
- Search by name or path. Choose **Current folder** to search descendants of the
  open folder. One- or two-character searches match filename prefixes.
- Searches show up to 250 matches. Refine the search text or scope for more
  specific results.
- Select a row and choose **Open**, or double-click it. Right-click for
  **Open containing folder** and **Copy full path**.
- Keyboard shortcuts include **Ctrl+F** for search, **Esc** to clear the query,
  **Alt+D** for the address bar, **F5** to refresh, and **Ctrl+Shift+C** to copy
  the selected path.
- Up to four drives can be scanned at once, and searches use the entries already
  indexed while the scan continues. The first scan of a large drive still
  depends on the speed of that drive and its number of files.
- File notifications update the index during use. Background scans reconcile
  missed changes on launch and every twenty minutes. Inaccessible directories
  and filesystem redirects may be skipped. With `WINEPREFIX` set, DocuFind
  skips Wine's host-wide `Z:\` mapping. Dedicated drive mappings can be indexed
  separately.

## Build locally

Run `mvn clean verify` from the `docufind` folder with JDK 17 or later.
To run the app, use `java -jar target/docufind.jar`.

To package the Windows x64 installer locally, install JDK 21, Maven and WiX
Toolset 3.14 on Windows. Add their executables to PATH and run
`build-windows.cmd` from the project folder. The script runs the tests before
creating `target\windows\installer\DocuFind-2.2.0.exe`.

## Index and source layout

The local SQLite database stores names, paths, sizes and timestamps under
`%LOCALAPPDATA%\DocuFindData` on Windows. DocuFind does not read or copy file
contents. The database is compatible with Explorer 2.0.

| File | Responsibility |
| --- | --- |
| `DocuFind.java` | Starts the app and opens the local index |
| `ExplorerWindow.java` | Handles navigation, search controls, and selection |
| `ExplorerStyle.java` | Defines the palette, icons, and reusable Swing controls |
| `FileTable.java` | Displays and sorts files, folders, and drives |
| `DriveIndexing.java` | Scans drives and tracks filesystem changes |
| `FileIndex.java` | Stores and queries indexed entries in SQLite |
