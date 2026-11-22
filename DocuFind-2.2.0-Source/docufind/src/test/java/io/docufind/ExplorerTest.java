package io.docufind;

import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public final class ExplorerTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void beginsIndexingTwoDrivesTogether() throws Exception {
        Path first = temp.newFolder("first-drive").toPath();
        Path second = temp.newFolder("second-drive").toPath();
        Path firstFile = Files.writeString(first.resolve("first-result.txt"), "one");
        Path secondFile = Files.writeString(second.resolve("second-result.txt"), "two");
        CountDownLatch started = new CountDownLatch(2);
        FileIndex index = index();
        try (DriveIndexing service = new DriveIndexing(index, temp.getRoot().toPath().resolve("data"),
                status -> {
                    if (status.scanning()) {
                        started.countDown();
                        try { started.await(3, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }, List.of(first, second), Duration.ofHours(1))) {
            service.start();
            assertTrue("Both drive scans should start together", started.await(2, TimeUnit.SECONDS));
            await(() -> paths(index, "first-result", null, first).contains(firstFile)
                    && paths(index, "second-result", null, second).contains(secondFile));
        }
    }

    @Test public void discoversFilesRanksMatchesAndLimitsScope() throws Exception {
        Path drive = temp.newFolder("drive").toPath();
        Path projects = Files.createDirectory(drive.resolve("Projects"));
        Path exact = Files.writeString(drive.resolve("Budget"), "exact");
        Path starts = Files.writeString(projects.resolve("Budget 2026.xlsx"), "prefix");
        Path contains = Files.writeString(projects.resolve("Department budget.xlsx"), "name");
        Path parent = Files.createDirectory(drive.resolve("budget archive"));
        Path pathOnly = Files.writeString(parent.resolve("notes.txt"), "path");
        Path unicode = Files.writeString(projects.resolve("Café [2].txt"), "text");
        FileIndex index = index();
        try (DriveIndexing service = start(index, drive)) {
            await(() -> service.roots().contains(drive) && paths(index, "budget", null, drive).contains(pathOnly));
            var results = paths(index, "BuDgEt", null, drive);
            assertEquals(List.of(exact, starts, parent, contains, pathOnly), results);
            assertEquals(List.of(starts, contains), paths(index, "budget", projects, drive));
            assertEquals(List.of(unicode), paths(index, "CAFÉ [2]", null, drive));
            assertEquals(List.of(exact, starts, parent), paths(index, "bu", null, drive));
            assertEquals(List.of(), paths(index, "budget", null, projects));
        }
    }

    @Test public void followsChangesAndReconcilesAfterRestart() throws Exception {
        Path drive = temp.newFolder("drive").toPath();
        Path original = Files.writeString(drive.resolve("initial-record.txt"), "hello");
        FileIndex index = index();
        Path renamed = drive.resolve("renamed-folder");
        try (DriveIndexing service = start(index, drive)) {
            await(() -> service.revision() > 0 && paths(index, "initial-record", null, drive).contains(original));
            Path added = Files.createDirectory(drive.resolve("new-folder"));
            Path item = Files.writeString(added.resolve("watch-target.txt"), "first");
            await(() -> paths(index, "watch-target", null, drive).contains(item));
            Files.move(added, renamed);
            await(() -> paths(index, "watch-target", null, drive).equals(List.of(renamed.resolve(item.getFileName()))));
            Files.delete(original);
            await(() -> paths(index, "initial-record", null, drive).isEmpty());
        }

        Files.delete(renamed.resolve("watch-target.txt"));
        Path replacement = Files.writeString(drive.resolve("offline-change.txt"), "offline");
        FileIndex reopened = index();
        assertFalse(paths(reopened, "watch-target", null, drive).isEmpty());
        try (DriveIndexing service = start(reopened, drive)) {
            await(() -> service.revision() > 0 && paths(reopened, "watch-target", null, drive).isEmpty()
                    && paths(reopened, "offline-change", null, drive).equals(List.of(replacement)));
        }
    }

    @Test public void browsesFoldersWithoutAnIndex() throws Exception {
        Path folder = temp.newFolder("browse").toPath();
        Path directory = Files.createDirectory(folder.resolve("Z folder"));
        Path file = Files.writeString(folder.resolve("A file.txt"), "four");
        var rows = ExplorerWindow.readFolder(folder).entries();
        assertEquals(List.of(directory, file), rows.stream().map(FileEntry::path).toList());
        assertTrue(rows.get(0).folder());
        assertEquals(4, rows.get(1).size());
    }

    @Test public void capsCommonResultsButRetainsRareSubstringMatches() throws Exception {
        Path drive = temp.newFolder("drive").toPath();
        FileIndex index = index();
        long scan = index.nextScanId();
        List<FileEntry> records = new ArrayList<>();
        for (int number = 0; number < 3000; number++) {
            records.add(new FileEntry(drive.resolve("common-" + number + ".dat"), false, number, 0));
        }
        Path rare = drive.resolve("common-rare.txt");
        records.add(new FileEntry(rare, false, 42, 0));
        index.save(records, drive, scan);
        var results = index.search("common", null, List.of(drive));
        assertTrue(results.more());
        assertEquals(250, results.files().size());
        assertEquals(List.of(rare), paths(index, "n-rare", null, drive));
    }

    @Test public void skipsSymbolicDirectoryAndItsDescendantsWhenSupported() throws Exception {
        Path drive = temp.newFolder("drive").toPath();
        Path outside = temp.newFolder("outside").toPath();
        Files.writeString(outside.resolve("outside-document.txt"), "test");
        try {
            Files.createSymbolicLink(drive.resolve("redirected"), outside);
        } catch (UnsupportedOperationException | FileSystemException | SecurityException e) {
            org.junit.Assume.assumeNoException(e);
        }
        Path ready = Files.writeString(drive.resolve("sentinel.txt"), "test");
        FileIndex index = index();
        try (DriveIndexing service = start(index, drive)) {
            await(() -> service.revision() > 0 && paths(index, "sentinel", null, drive).equals(List.of(ready)));
            assertTrue(paths(index, "outside-document", null, drive).isEmpty());
        }
    }

    @Test public void acceptsAnAliasAboveTheRootButRejectsARedirectedSubfolder() throws Exception {
        Path realBase = temp.newFolder("actual").toPath();
        Path root = Files.createDirectory(realBase.resolve("drive"));
        Path subfolder = Files.createDirectory(root.resolve("normal"));
        Path outside = temp.newFolder("outside").toPath();
        Path alias = temp.getRoot().toPath().resolve("alias");
        try {
            Files.createSymbolicLink(alias, realBase);
            Files.createSymbolicLink(root.resolve("redirect"), outside);
        } catch (UnsupportedOperationException | FileSystemException | SecurityException e) {
            org.junit.Assume.assumeNoException(e);
        }
        Path aliasedRoot = alias.resolve(root.getFileName());
        assertFalse(DriveIndexing.leavesIndexedPath(aliasedRoot.resolve(subfolder.getFileName()),
                aliasedRoot, aliasedRoot.toRealPath()));
        assertTrue(DriveIndexing.leavesIndexedPath(aliasedRoot.resolve("redirect"),
                aliasedRoot, aliasedRoot.toRealPath()));
    }

    private FileIndex index() throws Exception {
        return new FileIndex(temp.getRoot().toPath().resolve("data/explorer.db"));
    }

    private DriveIndexing start(FileIndex index, Path root) throws Exception {
        var service = new DriveIndexing(index, temp.getRoot().toPath().resolve("data"),
                status -> {}, List.of(root), Duration.ofHours(1));
        service.start();
        return service;
    }

    private List<Path> paths(FileIndex index, String query, Path folder, Path root) throws Exception {
        return index.search(query, folder, List.of(root)).files().stream().map(FileEntry::path).toList();
    }

    private void await(Condition condition) throws Exception {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        do {
            if (condition.ready()) return;
            Thread.sleep(60);
        } while (System.nanoTime() < limit);
        fail("Timed out waiting for the expected index update");
    }

    private interface Condition {
        boolean ready() throws Exception;
    }
}
