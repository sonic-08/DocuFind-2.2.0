package io.docufind;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.*;

final class DriveIndexing implements AutoCloseable {
    record Status(String message, boolean scanning) {}
    private record WatchedFolder(Path path, Path root) {}
    private record Change(Path root, boolean inspectChildren) {}
    private static final int BATCH_SIZE = 1024;
    private static final int MAX_FALLBACK_WATCHES = 4096;
    private static final int MAX_PENDING = 8192;
    private static final WatchEvent.Modifier RECURSIVE_WATCH = recursiveWatchModifier();

    private final FileIndex index;
    private final Path databaseDirectory;
    private final Consumer<Status> status;
    private final WatchService watcher;
    private final Map<WatchKey, WatchedFolder> watched = new ConcurrentHashMap<>();
    private final Set<Path> recursive = ConcurrentHashMap.newKeySet();
    private final Map<Path, Path> resolvedRoots = new HashMap<>();
    private final Map<Path, Change> changes = new LinkedHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            task -> daemon("drive-indexer", task));
    private final ExecutorService scanners = Executors.newFixedThreadPool(4,
            task -> daemon("drive-scanner", task));
    private final Thread watchThread;
    private final List<Path> configuredRoots;
    private final Duration interval;
    private volatile List<Path> roots = List.of();
    private final AtomicLong revision = new AtomicLong();
    private boolean missedChanges;
    private volatile boolean closed;

    DriveIndexing(FileIndex index, Path databaseDirectory, Consumer<Status> status)
            throws IOException {
        this(index, databaseDirectory, status, null, Duration.ofMinutes(20));
    }

    DriveIndexing(FileIndex index, Path databaseDirectory, Consumer<Status> status,
                  List<Path> configuredRoots, Duration interval) throws IOException {
        this.index = index;
        this.databaseDirectory = databaseDirectory.toAbsolutePath().normalize();
        this.status = status;
        this.configuredRoots = configuredRoots;
        this.interval = interval;
        watcher = FileSystems.getDefault().newWatchService();
        watchThread = daemon("drive-watcher", this::readChanges);
    }

    void start() {
        watchThread.start();
        worker.scheduleWithFixedDelay(this::reconcileAll, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(this::applyChanges, 800, 800, TimeUnit.MILLISECONDS);
    }

    List<Path> roots() {
        return roots;
    }

    long revision() {
        return revision.get();
    }

    private List<Path> availableRoots() {
        if (configuredRoots != null) return configuredRoots;
        boolean wine = System.getenv("WINEPREFIX") != null;
        return Arrays.stream(File.listRoots()).map(File::toPath)
                .filter(path -> !wine || !path.toString().equalsIgnoreCase("Z:\\"))
                .filter(Files::isDirectory)
                .toList();
    }

    private void reconcileAll() {
        if (closed) return;
        try {
            roots = availableRoots();
            resolvedRoots.keySet().retainAll(roots);
            dropDetachedWatches();
            revision.incrementAndGet();
            if (roots.isEmpty()) {
                status.accept(new Status("No accessible drives", false));
                return;
            }

            boolean complete = true;
            List<Future<Boolean>> scans = new ArrayList<>();
            for (Path root : roots) {
                if (closed) return;
                if (FileEntry.WINDOWS) {
                    try {
                        resolvedRoots.put(root, root.toRealPath());
                    } catch (IOException | SecurityException e) {
                        resolvedRoots.remove(root);
                        complete = false;
                        continue;
                    }
                }
                scans.add(scanners.submit(() -> {
                    boolean useRecursiveWatch = registerRecursive(root);
                    status.accept(new Status("Scanning " + root + " in the background", true));
                    return scan(root, root, useRecursiveWatch);
                }));
            }
            for (Future<Boolean> scan : scans) {
                try {
                    if (!scan.get()) complete = false;
                } catch (ExecutionException e) {
                    complete = false;
                    System.err.println("DocuFind indexing: " + e.getCause());
                }
            }
            status.accept(new Status(complete ? "Index up to date" : "Index updated for accessible locations", false));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            reportFailure(e);
        }
    }

    private boolean scan(Path start, Path root, boolean useRecursiveWatch) throws IOException, SQLException {
        long generation = index.nextScanId();
        List<FileEntry> batch = new ArrayList<>(BATCH_SIZE);
        boolean[] complete = {true};
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attrs)
                        throws IOException {
                    if (closed) return FileVisitResult.TERMINATE;
                    if (excluded(folder)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!useRecursiveWatch) registerFolder(folder, root);
                    add(FileEntry.from(folder, attrs));
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                        throws IOException {
                    if (closed) return FileVisitResult.TERMINATE;
                    if (!excluded(path) && attrs.isRegularFile()) add(FileEntry.from(path, attrs));
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path path, IOException failure) {
                    if (!(failure instanceof NoSuchFileException)) complete[0] = false;
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) {
                    if (failure != null) complete[0] = false;
                    return FileVisitResult.CONTINUE;
                }

                private void add(FileEntry entry) throws IOException {
                    batch.add(entry);
                    if (batch.size() < BATCH_SIZE) return;
                    try {
                        save(batch, root, generation);
                    } catch (SQLException e) {
                        throw new IOException("Could not update the file index", e);
                    }
                }
            });
        } catch (NoSuchFileException e) {
            // A location can disappear between notification and traversal.
        }
        if (!closed) {
            save(batch, root, generation);
            if (!start.equals(root) || Files.isDirectory(root)) index.removeStale(start, generation);
            revision.incrementAndGet();
        }
        return complete[0];
    }

    private void save(List<FileEntry> batch, Path root, long scanId) throws SQLException {
        if (batch.isEmpty()) return;
        index.save(batch, root, scanId);
        batch.clear();
        revision.incrementAndGet();
    }

    private boolean excluded(Path path) {
        return path.startsWith(databaseDirectory) || (!FileEntry.WINDOWS
                && (path.startsWith(Path.of("/proc")) || path.startsWith(Path.of("/sys"))
                    || path.startsWith(Path.of("/dev"))));
    }

    static boolean leavesIndexedPath(Path folder, Path root, Path resolvedRoot) {
        if (resolvedRoot == null) return true;
        try {
            // Windows can expand a short name or an alias above the indexed root.
            // Compare the descendants underneath the root's actual location.
            Path expected = resolvedRoot.resolve(root.relativize(folder));
            return !folder.toRealPath().equals(expected);
        } catch (IOException | SecurityException e) {
            return true;
        }
    }

    private boolean registerRecursive(Path root) {
        if (RECURSIVE_WATCH == null) return false;
        if (recursive.contains(root)) return true;
        try {
            WatchKey key = root.register(watcher, new WatchEvent.Kind<?>[]{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY},
                    RECURSIVE_WATCH);
            watched.put(key, new WatchedFolder(root, root));
            recursive.add(root);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    private static WatchEvent.Modifier recursiveWatchModifier() {
        if (!FileEntry.WINDOWS) return null;
        try {
            Class<?> type = Class.forName("com.sun.nio.file.ExtendedWatchEventModifier");
            for (Object value : type.getEnumConstants()) {
                if (value instanceof Enum<?> constant && constant.name().equals("FILE_TREE")) {
                    return (WatchEvent.Modifier) value;
                }
            }
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            // A stripped or different runtime uses individual folder watches.
        }
        return null;
    }

    private void registerFolder(Path folder, Path root) {
        if (watched.size() >= MAX_FALLBACK_WATCHES) return;
        try {
            WatchKey key = folder.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            watched.put(key, new WatchedFolder(folder, root));
        } catch (IOException | SecurityException e) {
            // The scheduled scan covers directories that cannot be watched.
        }
    }

    private void dropDetachedWatches() {
        watched.forEach((key, folder) -> {
            if (!roots.contains(folder.root())) {
                key.cancel();
                watched.remove(key);
                recursive.remove(folder.root());
            }
        });
    }

    private void readChanges() {
        try {
            while (!closed) {
                WatchKey key = watcher.take();
                WatchedFolder folder = watched.get(key);
                if (folder != null) {
                    synchronized (changes) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == OVERFLOW || changes.size() >= MAX_PENDING) {
                                missedChanges = true;
                                changes.clear();
                                break;
                            }
                            Path path = folder.path().resolve((Path) event.context()).normalize();
                            if (!path.startsWith(folder.root()) || excluded(path)) continue;
                            boolean structural = event.kind() != ENTRY_MODIFY;
                            changes.merge(path, new Change(folder.root(), structural),
                                    (old, last) -> new Change(last.root(),
                                            old.inspectChildren() || last.inspectChildren()));
                        }
                    }
                }
                if (!key.reset() && watched.remove(key) != null && folder != null && folder.path().equals(folder.root())) {
                    recursive.remove(folder.root());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // Closing the application wakes this thread.
        }
    }

    private void applyChanges() {
        Map<Path, Change> pending;
        boolean rebuild;
        synchronized (changes) {
            pending = new LinkedHashMap<>(changes);
            changes.clear();
            rebuild = missedChanges;
            missedChanges = false;
        }
        if (rebuild) {
            reconcileAll();
            return;
        }

        try {
            for (var item : pending.entrySet()) {
                if (closed) return;
                Path path = item.getKey();
                Change change = item.getValue();
                if (!roots.contains(change.root())) continue;
                if (FileEntry.WINDOWS && !path.equals(change.root())
                        && leavesIndexedPath(path.getParent(), change.root(), resolvedRoots.get(change.root()))) {
                    continue;
                }
                if (change.inspectChildren() || !Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    scan(path, change.root(), recursive.contains(change.root()));
                }
            }
        } catch (IOException | SQLException | RuntimeException e) {
            reportFailure(e);
            synchronized (changes) {
                missedChanges = true;
            }
        }
    }

    private void reportFailure(Exception e) {
        if (closed) return;
        System.err.println("DocuFind indexing: " + e);
        status.accept(new Status("Indexing interrupted · will retry", false));
    }

    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override public void close() throws IOException {
        closed = true;
        worker.shutdownNow();
        scanners.shutdownNow();
        watcher.close();
    }
}
