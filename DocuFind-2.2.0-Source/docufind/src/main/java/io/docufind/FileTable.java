package io.docufind;

import java.awt.*;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

import static io.docufind.ExplorerStyle.*;

final class FileTable extends JTable {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Icon DRIVE = badge(Glyph.DRIVE, 26, BLUE, FILE_TINT);
    private static final Icon FOLDER = badge(Glyph.FOLDER, 26, ORANGE, FOLDER_TINT);
    private static final Icon FILE = badge(Glyph.FILE, 26, BLUE, FILE_TINT);
    private final Model entries = new Model();
    private final transient TableRowSorter<Model> sorter = new TableRowSorter<>(entries);

    FileTable() {
        setModel(entries);
        setRowSorter(sorter);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setRowHeight(44);
        setSelectionBackground(SELECTION);
        setSelectionForeground(INK);
        setBackground(PAPER);
        setShowGrid(false);
        setFillsViewportHeight(true);
        setIntercellSpacing(new Dimension(0, 0));
        getTableHeader().setReorderingAllowed(false);
        getTableHeader().setPreferredSize(new Dimension(1, 39));
        getTableHeader().setFont(font(12, Font.BOLD));
        getTableHeader().setBackground(HEADER);
        getTableHeader().setForeground(MUTED);
        getTableHeader().setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE));
        Renderer renderer = new Renderer();
        setDefaultRenderer(Object.class, renderer);
        setDefaultRenderer(Long.class, renderer);
        int[] widths = {290, 78, 223, 141, 76};
        for (int i = 0; i < widths.length; i++) getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        var defaultHeader = getTableHeader().getDefaultRenderer();
        getTableHeader().setDefaultRenderer((table, value, selected, focus, row, column) -> {
            Component header = defaultHeader.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (header instanceof JLabel label) {
                label.setHorizontalAlignment(SwingConstants.LEFT);
                label.setBorder(pad(0, column == 0 ? 15 : 8, 0, 7));
                label.setFont(font(12, Font.BOLD));
                label.setBackground(HEADER);
                label.setForeground(MUTED);
                label.setOpaque(true);
            }
            return header;
        });
    }

    @Override public void doLayout() {
        var location = getColumnModel().getColumn(2);
        boolean compact = getWidth() < 630;
        if (compact && location.getMaxWidth() != 0) {
            location.setMinWidth(0);
            location.setMaxWidth(0);
            location.setPreferredWidth(0);
        } else if (!compact && location.getMaxWidth() == 0) {
            location.setMaxWidth(Integer.MAX_VALUE);
            location.setMinWidth(45);
            location.setPreferredWidth(223);
        }
        super.doLayout();
    }

    FileEntry selectedEntry() {
        int row = getSelectedRow();
        return row < 0 ? null : entries.files.get(convertRowIndexToModel(row));
    }

    void showEntries(List<FileEntry> files, boolean resetSort) {
        FileEntry selected = selectedEntry();
        if (resetSort) sorter.setSortKeys(List.of());
        entries.setFiles(files);
        if (selected == null) return;
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).path().equals(selected.path())) {
                int row = convertRowIndexToView(i);
                setRowSelectionInterval(row, row);
                break;
            }
        }
    }

    private static String kind(FileEntry entry) {
        if (entry.path().getParent() == null) return "Drive";
        if (entry.folder()) return "Folder";
        int dot = entry.name().lastIndexOf('.');
        if (dot < 1 || entry.name().length() - dot > 6) return "File";
        return entry.name().substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        int unit = 0;
        double size = bytes / 1024.0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return new DecimalFormat("0.#").format(size) + " " + units[unit];
    }

    private final class Renderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        Renderer() {
            putClientProperty("html.disable", true);
        }

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, null, selected, focus, row, column);
            FileEntry entry = entries.files.get(convertRowIndexToModel(row));
            setFont(font(column == 0 ? 13 : 12, column == 0 ? Font.BOLD : Font.PLAIN));
            setForeground(column == 0 ? INK : MUTED);
            setBackground(selected ? SELECTION : row % 2 == 0 ? PAPER : new Color(0xFAFCFE));
            setBorder(pad(0, column == 0 ? 15 : 8, 0, 9));
            setIcon(null);
            setIconTextGap(10);
            setToolTipText(null);
            setHorizontalAlignment(column == 4 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            switch (column) {
                case 0 -> {
                    setText(entry.name());
                    setIcon(entry.path().getParent() == null ? DRIVE : entry.folder() ? FOLDER : FILE);
                    setToolTipText(" " + entry.path());
                }
                case 2 -> {
                    setText(value.toString());
                    setToolTipText(" " + value);
                }
                case 3 -> setText(entry.modified() <= 0 ? "" : DATE.format(Instant.ofEpochMilli(entry.modified())));
                case 4 -> setText(entry.folder() ? "" : formatSize(entry.size()));
                default -> setText(value.toString());
            }
            return this;
        }
    }

    private static final class Model extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"Name", "Type", "Location", "Date modified", "Size"};
        private transient List<FileEntry> files = List.of();

        void setFiles(List<FileEntry> files) {
            this.files = files;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return files.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Class<?> getColumnClass(int column) { return column >= 3 ? Long.class : String.class; }

        @Override public Object getValueAt(int row, int column) {
            FileEntry entry = files.get(row);
            return switch (column) {
                case 0 -> entry.name();
                case 1 -> kind(entry);
                case 2 -> entry.path().getParent() == null ? entry.path().toString() : entry.path().getParent().toString();
                case 3 -> entry.modified();
                case 4 -> entry.folder() ? -1L : entry.size();
                default -> throw new IndexOutOfBoundsException(column);
            };
        }
    }
}
