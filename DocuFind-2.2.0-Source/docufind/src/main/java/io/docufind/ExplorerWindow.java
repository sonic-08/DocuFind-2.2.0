package io.docufind;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import static io.docufind.ExplorerStyle.*;

final class ExplorerWindow extends JPanel {
    private static final long serialVersionUID = 1L;
    private final transient FileIndex index;
    private final transient DriveIndexing indexing;
    private final transient AutoCloseable appLock;
    private final transient Deque<Optional<Path>> previous = new ArrayDeque<>();
    private final transient Deque<Optional<Path>> next = new ArrayDeque<>();
    private final DefaultListModel<Path> driveModel = new DefaultListModel<>();
    private final JList<Path> drives = new JList<>(driveModel);
    private final JTextField address = field("Enter a folder path", 13);
    private final JTextField search = field("Search files and folders", 16);
    private final JComboBox<String> scope = scopeSelector();
    private final JButton back = button("", Glyph.BACK, "Back (Alt+Left)", false, false);
    private final JButton forward = button("", Glyph.FORWARD, "Forward (Alt+Right)", false, false);
    private final JButton up = button("", Glyph.UP, "Parent folder (Alt+Up)", false, false);
    private final JButton refresh = button("", Glyph.REFRESH, "Refresh (F5)", false, false);
    private final JButton clear = button("", Glyph.CLOSE, "Clear search (Esc)", false, false);
    private final JButton open = button("Open", Glyph.OPEN, "Open selected item (Enter)", true, false);
    private final JButton copy = button("", Glyph.COPY, "Copy full path (Ctrl+Shift+C)", false, false);
    private final JButton computer = button("This PC", Glyph.COMPUTER, "Show drives", false, true);
    private final JButton home = button("Home folder", Glyph.HOME, "Browse your user folder", false, true);
    private final JLabel heading = label("This PC", 34, Font.BOLD, INK);
    private final JLabel resultCount = label("Detecting drives…", 13, Font.PLAIN, MUTED);
    private final JLabel viewTitle = label("Drives", 14, Font.BOLD, INK);
    private final JLabel indexCaption = label("Indexing", 13, Font.BOLD, SIDEBAR_TEXT);
    private final JLabel indexStatus = label("Detecting drives…", 11, Font.PLAIN, SIDEBAR_MUTED);
    private final JLabel selection = label("Select an item to see its path.", 12, Font.PLAIN, MUTED);
    private final JProgressBar busy = new JProgressBar();
    private final FileTable table = new FileTable();
    private final CardLayout resultsLayout = new CardLayout();
    private final JPanel results = new JPanel(resultsLayout);
    private final JLabel emptyIcon = new JLabel(badge(Glyph.DRIVE, 58, BLUE, FILE_TINT));
    private final JLabel emptyTitle = label("Detecting drives", 19, Font.BOLD, INK);
    private final JLabel emptyDetail = label("Search is available as files are indexed.", 13, Font.PLAIN, MUTED);
    private final Timer debounce = new Timer(140, event -> loadView());
    private final Timer updates = new Timer(1400, event -> checkIndex());
    private transient SwingWorker<View, Void> task;
    private transient List<Path> mounted = List.of();
    private transient Path folder;
    private long lastRevision = -1;
    private boolean indexScanning = true;
    private boolean syncingDrives;

    ExplorerWindow(FileIndex index, Path dataDirectory, AutoCloseable appLock) throws IOException {
        super(new BorderLayout());
        this.index = index;
        this.appLock = appLock;
        indexing = new DriveIndexing(index, dataDirectory,
                status -> EventQueue.invokeLater(() -> showStatus(status)));
        debounce.setRepeats(false);
        add(sidebar(), BorderLayout.WEST);
        add(workspace(), BorderLayout.CENTER);
        bindActions();
        address.setText("This PC");
        updateNavigation();
        updateSelection();
    }

    void open() {
        JFrame frame = new JFrame("DocuFind Explorer");
        frame.setIconImage(appIcon(64));
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setContentPane(this);
        frame.setMinimumSize(new Dimension(790, 510));
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setSize(Math.min(1160, screen.width), Math.min(760, screen.height));
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { close(); }
        });
        frame.setVisible(true);
        indexing.start();
        updates.start();
    }

    void close() {
        debounce.stop();
        updates.stop();
        if (task != null) task.cancel(true);
        try { indexing.close(); } catch (IOException e) { System.err.println(e); }
        try { appLock.close(); } catch (Exception e) { System.err.println(e); }
    }

    private JPanel workspace() {
        JPanel page = panel(CANVAS, new BorderLayout(0, 18));
        page.setBorder(pad(24, 26, 22, 26));
        JPanel top = panel(null, new BorderLayout(0, 17));
        JPanel title = panel(null, new BorderLayout(0, 3));
        title.add(heading, BorderLayout.NORTH);
        title.add(resultCount, BorderLayout.SOUTH);
        top.add(title, BorderLayout.NORTH);
        top.add(searchBar(), BorderLayout.CENTER);
        page.add(top, BorderLayout.NORTH);
        page.add(filePane(), BorderLayout.CENTER);
        return page;
    }

    private JPanel searchBar() {
        JPanel bar = surface(PAPER, LINE, 17, new BorderLayout(12, 0));
        bar.setBorder(pad(10, 15, 10, 10));
        bar.setPreferredSize(new Dimension(100, 61));
        JLabel glass = new JLabel(icon(Glyph.SEARCH, 24, BLUE));
        bar.add(glass, BorderLayout.WEST);
        describe(search, "Search by name or path (Ctrl+F)");
        bar.add(search, BorderLayout.CENTER);

        JPanel options = panel(null, new FlowLayout(FlowLayout.RIGHT, 7, 0));
        clear.setVisible(false);
        JPanel keyHint = surface(HEADER, LINE, 8, new BorderLayout());
        keyHint.setBorder(pad(8, 8, 8, 8));
        keyHint.add(label("Ctrl+F", 11, Font.BOLD, MUTED));
        options.add(keyHint);
        options.add(clear);
        describe(scope, "Search scope");
        JPanel scopePanel = surface(HEADER, LINE, 9, new BorderLayout());
        scopePanel.setBorder(pad(1, 5, 1, 3));
        scopePanel.add(scope);
        options.add(scopePanel);
        bar.add(options, BorderLayout.EAST);
        return bar;
    }

    private JPanel sidebar() {
        JPanel side = panel(SIDEBAR, new BorderLayout(0, 22));
        side.setPreferredSize(new Dimension(208, 0));
        side.setBorder(pad(25, 15, 17, 15));

        JPanel top = panel(null, new BorderLayout(0, 25));
        JPanel brand = panel(null, new BorderLayout(10, 0));
        brand.setBorder(pad(0, 3, 0, 0));
        brand.add(new JLabel(badge(Glyph.BRAND, 43, SIDEBAR, TEAL)), BorderLayout.WEST);
        JPanel wordmark = panel(null, new GridLayout(2, 1, 0, 0));
        wordmark.add(label("DocuFind", 19, Font.BOLD, SIDEBAR_TEXT));
        wordmark.add(label("Explorer", 12, Font.PLAIN, SIDEBAR_MUTED));
        brand.add(wordmark, BorderLayout.CENTER);
        top.add(brand, BorderLayout.NORTH);

        JPanel places = panel(null, new GridLayout(0, 1, 0, 4));
        JLabel placesLabel = label("Places", 12, Font.BOLD, SIDEBAR_MUTED);
        placesLabel.setBorder(pad(0, 11, 0, 0));
        places.add(placesLabel);
        places.add(computer);
        places.add(home);
        top.add(places, BorderLayout.SOUTH);
        side.add(top, BorderLayout.NORTH);

        JPanel volumes = panel(null, new BorderLayout(0, 6));
        JLabel drivesLabel = label("Drives", 12, Font.BOLD, SIDEBAR_MUTED);
        drivesLabel.setBorder(pad(0, 11, 1, 0));
        volumes.add(drivesLabel, BorderLayout.NORTH);
        drives.setBackground(SIDEBAR);
        drives.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        drives.setFixedCellHeight(37);
        drives.getAccessibleContext().setAccessibleName("Available drives");
        Icon drive = icon(Glyph.DRIVE, 17, SIDEBAR_MUTED);
        Icon current = icon(Glyph.DRIVE, 17, TEAL);
        drives.setCellRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int position, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, "Drive " + value, position, selected, focus);
                setBackground(selected ? SIDEBAR_HOVER : SIDEBAR);
                setForeground(SIDEBAR_TEXT);
                setFont(font(13, selected ? Font.BOLD : Font.PLAIN));
                setBorder(pad(0, 11, 0, 5));
                setIcon(selected ? current : drive);
                setIconTextGap(9);
                return this;
            }
        });
        volumes.add(scroll(drives, SIDEBAR), BorderLayout.CENTER);
        side.add(volumes, BorderLayout.CENTER);

        JPanel status = surface(SIDEBAR_CARD, null, 14, new BorderLayout(0, 7));
        status.setBorder(pad(11, 11, 10, 11));
        JPanel statusText = panel(null, new GridLayout(2, 1, 0, 3));
        statusText.add(indexCaption);
        statusText.add(indexStatus);
        status.add(statusText, BorderLayout.NORTH);
        busy.setIndeterminate(true);
        busy.setForeground(TEAL);
        busy.setBackground(SIDEBAR_HOVER);
        busy.setBorderPainted(false);
        busy.setPreferredSize(new Dimension(100, 4));
        status.add(busy, BorderLayout.SOUTH);
        side.add(status, BorderLayout.SOUTH);
        return side;
    }

    private JPanel filePane() {
        JPanel card = surface(PAPER, LINE, 17, new BorderLayout());
        card.setBorder(pad(1, 1, 1, 1));

        JPanel header = panel(null, new BorderLayout(0, 14));
        header.setBorder(pad(14, 15, 13, 15));
        JPanel location = panel(null, new BorderLayout(9, 0));
        JPanel arrows = panel(null, new GridLayout(1, 4, 4, 0));
        for (JButton button : List.of(back, forward, up, refresh)) arrows.add(button);
        location.add(arrows, BorderLayout.WEST);
        JPanel bar = surface(HEADER, LINE, 11, new BorderLayout(8, 0));
        bar.setBorder(pad(0, 10, 0, 8));
        bar.add(new JLabel(icon(Glyph.FOLDER, 18, MUTED)), BorderLayout.WEST);
        describe(address, "Folder path (Alt+D)");
        bar.add(address, BorderLayout.CENTER);
        location.add(bar, BorderLayout.CENTER);
        header.add(location, BorderLayout.NORTH);

        JPanel actions = panel(null, new BorderLayout(9, 0));
        JPanel controls = panel(null, new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.add(copy);
        controls.add(open);
        actions.add(viewTitle, BorderLayout.WEST);
        actions.add(controls, BorderLayout.EAST);
        header.add(actions, BorderLayout.SOUTH);
        card.add(header, BorderLayout.NORTH);

        JPanel empty = panel(PAPER, new GridBagLayout());
        JPanel message = panel(null, new GridLayout(0, 1, 0, 8));
        emptyIcon.setHorizontalAlignment(SwingConstants.CENTER);
        emptyTitle.setHorizontalAlignment(SwingConstants.CENTER);
        emptyDetail.setHorizontalAlignment(SwingConstants.CENTER);
        message.add(emptyIcon);
        message.add(emptyTitle);
        message.add(emptyDetail);
        empty.add(message);
        results.setOpaque(false);
        JScrollPane listing = scroll(table, PAPER);
        listing.setColumnHeaderView(table.getTableHeader());
        results.add(listing, "table");
        results.add(empty, "empty");
        resultsLayout.show(results, "empty");
        card.add(results, BorderLayout.CENTER);

        selection.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE), pad(10, 15, 10, 15)));
        card.add(selection, BorderLayout.SOUTH);
        return card;
    }

    private static void describe(JComponent component, String help) {
        component.setToolTipText(help);
        component.getAccessibleContext().setAccessibleName(help);
    }

    private void bindActions() {
        back.addActionListener(event -> jump(previous, next));
        forward.addActionListener(event -> jump(next, previous));
        up.addActionListener(event -> goUp());
        refresh.addActionListener(event -> loadView());
        computer.addActionListener(event -> navigate(null));
        home.addActionListener(event -> navigate(homeFolder()));
        open.addActionListener(event -> openSelected());
        copy.addActionListener(event -> copySelectedPath());
        clear.addActionListener(event -> clearSearch());
        drives.addListSelectionListener(event -> {
            if (!syncingDrives && !event.getValueIsAdjusting() && drives.getSelectedValue() != null) {
                navigate(drives.getSelectedValue());
            }
        });
        address.addActionListener(event -> {
            if (address.getText().strip().equalsIgnoreCase("This PC")) {
                navigate(null);
                return;
            }
            try {
                Path entered = Path.of(address.getText().strip());
                if (!entered.isAbsolute()) throw new InvalidPathException(entered.toString(), "Use an absolute path");
                navigate(entered);
            } catch (InvalidPathException e) {
                showError("Enter a full folder path, such as C:\\Users.");
            }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { scheduleSearch(); }
            @Override public void removeUpdate(DocumentEvent event) { scheduleSearch(); }
            @Override public void changedUpdate(DocumentEvent event) { scheduleSearch(); }
        });
        scope.addActionListener(event -> scheduleSearch());
        bind(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK, "search", () -> focus(search));
        bind(KeyEvent.VK_D, InputEvent.ALT_DOWN_MASK, "address", () -> focus(address));
        bind(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK, "back", () -> jump(previous, next));
        bind(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK, "forward", () -> jump(next, previous));
        bind(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK, "up", this::goUp);
        bind(KeyEvent.VK_F5, 0, "refresh", this::loadView);
        bind(KeyEvent.VK_ESCAPE, 0, "clear-search", this::clearSearch);
        bind(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
                "copy-path", this::copySelectedPath);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && table.rowAtPoint(event.getPoint()) >= 0
                        && SwingUtilities.isLeftMouseButton(event)) openSelected();
            }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-file");
        table.getActionMap().put("open-file", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { openSelected(); }
        });
        table.getSelectionModel().addListSelectionListener(event -> updateSelection());
        table.setComponentPopupMenu(menu());
    }

    private void bind(int key, int modifiers, String id, Runnable action) {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, modifiers), id);
        getActionMap().put(id, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    private static void focus(JTextField field) {
        field.requestFocusInWindow();
        field.selectAll();
    }

    private void clearSearch() {
        if (search.getText().isEmpty()) return;
        search.setText("");
        search.requestFocusInWindow();
    }

    private void goUp() {
        if (folder != null) navigate(folder.getParent());
    }

    private static Path homeFolder() {
        return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
    }

    private void updateSelection() {
        FileEntry entry = selected();
        open.setEnabled(entry != null);
        copy.setEnabled(entry != null);
        selection.setText(entry == null ? "Select an item to see its path." : entry.path().toString());
        selection.setToolTipText(entry == null ? null : " " + entry.path());
    }

    private void copySelectedPath() {
        FileEntry entry = selected();
        if (entry == null) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(entry.path().toString()), null);
            selection.setText("Path copied  ·  " + entry.path());
        } catch (IllegalStateException e) {
            showError("The clipboard is busy. Try copying the path again.");
        }
    }

    private JPopupMenu menu() {
        JPopupMenu menu = new JPopupMenu();
        addItem(menu, "Open", this::openSelected);
        addItem(menu, "Open containing folder", () -> {
            FileEntry entry = selected();
            if (entry != null && entry.path().getParent() != null) navigate(entry.path().getParent());
        });
        addItem(menu, "Copy full path", this::copySelectedPath);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { selectPopupRow(event); }
            @Override public void mouseReleased(MouseEvent event) { selectPopupRow(event); }
            private void selectPopupRow(MouseEvent event) {
                if (event.isPopupTrigger() && table.rowAtPoint(event.getPoint()) >= 0) {
                    table.setRowSelectionInterval(table.rowAtPoint(event.getPoint()), table.rowAtPoint(event.getPoint()));
                }
            }
        });
        return menu;
    }

    private void addItem(JPopupMenu menu, String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(event -> action.run());
        menu.add(item);
    }

    private void navigate(Path path) {
        Path target = path == null ? null : path.toAbsolutePath().normalize();
        if (java.util.Objects.equals(target, folder) && search.getText().isBlank()) return;
        if (!java.util.Objects.equals(target, folder)) {
            previous.push(Optional.ofNullable(folder));
            next.clear();
        }
        showFolder(target);
    }

    private void jump(Deque<Optional<Path>> source, Deque<Optional<Path>> target) {
        if (source.isEmpty()) return;
        target.push(Optional.ofNullable(folder));
        showFolder(source.pop().orElse(null));
    }

    private void showFolder(Path target) {
        folder = target;
        address.setText(target == null ? "This PC" : target.toString());
        search.setText("");
        updateNavigation();
        loadView();
    }

    private void updateNavigation() {
        back.setEnabled(!previous.isEmpty());
        forward.setEnabled(!next.isEmpty());
        up.setEnabled(folder != null);
        computer.setSelected(folder == null);
        home.setSelected(homeFolder().equals(folder));
        clear.setVisible(!search.getText().isEmpty());
        scope.setEnabled(folder != null);
        if (folder == null && scope.getSelectedIndex() != 0) scope.setSelectedIndex(0);
        Path root = folder == null ? null : folder.getRoot();
        syncingDrives = true;
        try {
            if (root == null || !mounted.contains(root)) drives.clearSelection();
            else if (!root.equals(drives.getSelectedValue())) drives.setSelectedValue(root, true);
        } finally {
            syncingDrives = false;
        }
    }

    private void checkIndex() {
        List<Path> available = indexing.roots();
        if (!available.equals(mounted)) {
            mounted = available;
            drives.clearSelection();
            driveModel.clear();
            driveModel.addAll(available);
            updateNavigation();
            if (folder == null && search.getText().isBlank()) render(new View(driveEntries(), "Drives", false));
        }
        long currentRevision = indexing.revision();
        if (currentRevision != lastRevision) {
            if (search.getText().isBlank()) {
                lastRevision = currentRevision;
            } else if (!debounce.isRunning() && (task == null || task.isDone())) {
                lastRevision = currentRevision;
                loadView();
            }
        }
    }

    private List<FileEntry> driveEntries() {
        return mounted.stream().map(path -> new FileEntry(path, true, 0, 0)).toList();
    }

    private void showStatus(DriveIndexing.Status status) {
        indexScanning = status.scanning();
        indexCaption.setText(indexScanning ? "Indexing" : "Local index");
        indexStatus.setText(status.message());
        indexStatus.setToolTipText(status.message());
        busy.setVisible(indexScanning);
        checkIndex();
    }

    private void scheduleSearch() {
        if (task != null) task.cancel(true);
        debounce.restart();
        updateNavigation();
    }

    private void loadView() {
        debounce.stop();
        if (task != null) task.cancel(true);
        String query = search.getText().strip();
        Path requestedFolder = folder;
        List<Path> available = mounted;
        boolean scoped = scope.getSelectedIndex() == 1 && requestedFolder != null;
        if (query.isEmpty() && requestedFolder == null) {
            render(new View(driveEntries(), "Drives", false));
            return;
        }
        if (!query.isEmpty() && (query.length() > 512 || query.indexOf('\0') >= 0)) {
            render(new View(List.of(), "Search", false));
            emptyDetail.setText("Enter a search of up to 512 characters.");
            return;
        }
        Callable<View> fetch = query.isEmpty()
                ? () -> readFolder(requestedFolder)
                : () -> {
                    var found = index.search(query, scoped ? requestedFolder : null, available);
                    return new View(found.files(), scoped ? "Matches in this folder" : "Matches across drives", found.more());
                };
        if (query.isEmpty()) {
            table.showEntries(List.of(), false);
            heading.setText(folderTitle());
            viewTitle.setText("Files and folders");
            resultCount.setText("Loading…");
            emptyTitle.setText("Opening folder");
            emptyDetail.setText(requestedFolder.toString());
            resultsLayout.show(results, "empty");
        }
        task = new SwingWorker<>() {
            @Override protected View doInBackground() throws Exception { return fetch.call(); }
            @Override protected void done() {
                if (isCancelled() || task != this || !isDisplayable()) return;
                try {
                    render(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    table.showEntries(List.of(), false);
                    emptyIcon.setIcon(badge(query.isEmpty() ? Glyph.FOLDER : Glyph.SEARCH, 55, BLUE, FILE_TINT));
                    heading.setText(query.isEmpty() ? folderTitle() : "Search results");
                    viewTitle.setText(query.isEmpty() ? "Files and folders" : "Matching files and folders");
                    updateSelection();
                    resultCount.setText("Unavailable");
                    emptyTitle.setText(query.isEmpty() ? "Can’t open folder" : "Can’t search right now");
                    emptyDetail.setText(query.isEmpty() ? "Check that the folder exists and you can access it."
                            : "Try searching again in a moment.");
                    resultsLayout.show(results, "empty");
                    System.err.println(e.getCause());
                }
            }
        };
        task.execute();
    }

    static View readFolder(Path folder) throws IOException {
        List<FileEntry> entries = new ArrayList<>();
        try (var listing = Files.newDirectoryStream(folder)) {
            for (Path path : listing) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    var attrs = Files.readAttributes(path, BasicFileAttributes.class);
                    entries.add(FileEntry.from(path, attrs));
                } catch (IOException | SecurityException e) {
                    // A listing can change before its metadata is read.
                }
            }
        }
        entries.sort(Comparator.comparing(FileEntry::folder).reversed()
                .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER));
        return new View(entries, "Folder", false);
    }

    private String folderTitle() {
        if (folder == null) return "This PC";
        return folder.getFileName() == null ? folder.toString() : folder.getFileName().toString();
    }

    private void render(View view) {
        boolean searching = !search.getText().isBlank();
        table.showEntries(view.entries(), searching);
        updateSelection();
        heading.setText(searching ? "Search results" : folderTitle());
        viewTitle.setText(searching ? "Matching files and folders" : folder == null ? "Drives" : "Files and folders");
        int count = view.entries().size();
        String amount = (view.more() ? "First " : "") + count
                + (searching ? count == 1 ? " match" : " matches" : count == 1 ? " item" : " items");
        resultCount.setText(searching ? view.description() + "  ·  " + amount
                : folder == null ? count + (count == 1 ? " drive" : " drives") + " available" : amount);

        if (count > 0) {
            resultsLayout.show(results, "table");
            return;
        }
        Glyph glyph = searching ? Glyph.SEARCH : folder == null ? Glyph.DRIVE : Glyph.FOLDER;
        emptyIcon.setIcon(badge(glyph, 55, BLUE, FILE_TINT));
        emptyTitle.setText(searching ? "No matches yet" : folder == null ? "No drives to show" : "This folder is empty");
        emptyDetail.setText(searching && indexScanning ? "The drive scan is still in progress."
                : searching ? "Try a different name or check the search scope."
                : folder == null ? "Accessible drives will appear here." : "Use the address bar to open another folder.");
        resultsLayout.show(results, "empty");
    }

    private FileEntry selected() {
        return table.selectedEntry();
    }

    private void openSelected() {
        FileEntry file = selected();
        if (file == null) return;
        if (file.folder()) {
            navigate(file.path());
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws IOException {
                if (!Files.isRegularFile(file.path())) throw new IOException("This file has moved or been deleted.");
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    throw new IOException("No desktop file opener is available.");
                }
                Desktop.getDesktop().open(file.path().toFile());
                return null;
            }
            @Override protected void done() {
                if (!isDisplayable()) return;
                try { get(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (ExecutionException e) { showError(e.getCause().getMessage()); }
            }
        }.execute();
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "DocuFind", JOptionPane.INFORMATION_MESSAGE);
    }

    record View(List<FileEntry> entries, String description, boolean more) {}
}
