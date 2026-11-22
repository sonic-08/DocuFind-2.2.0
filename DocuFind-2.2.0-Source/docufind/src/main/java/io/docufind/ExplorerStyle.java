package io.docufind;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicComboBoxUI;

final class ExplorerStyle {
    static final Color CANVAS = new Color(0xF3F6F9);
    static final Color PAPER = Color.WHITE;
    static final Color INK = new Color(0x172639);
    static final Color MUTED = new Color(0x536477);
    static final Color FAINT = new Color(0x708095);
    static final Color LINE = new Color(0xDDE5ED);
    static final Color HEADER = new Color(0xF6F8FA);
    static final Color SELECTION = new Color(0xE2F0FA);
    static final Color BLUE = new Color(0x205CB4);
    static final Color BLUE_DARK = new Color(0x184C9A);
    static final Color SIDEBAR = new Color(0x111F32);
    static final Color SIDEBAR_CARD = new Color(0x1D2F46);
    static final Color SIDEBAR_HOVER = new Color(0x273D57);
    static final Color SIDEBAR_TEXT = new Color(0xEDF5FF);
    static final Color SIDEBAR_MUTED = new Color(0xA9BACD);
    static final Color TEAL = new Color(0x79E5CA);
    static final Color ORANGE = new Color(0xA35F13);
    static final Color FOLDER_TINT = new Color(0xFFF0D8);
    static final Color FILE_TINT = new Color(0xE9F1FC);
    private static final String FONT = FileEntry.WINDOWS ? "Segoe UI" : Font.SANS_SERIF;

    enum Glyph {
        BRAND, SEARCH, DRIVE, COMPUTER, FOLDER, FILE, HOME, BACK, FORWARD, UP, REFRESH, CLOSE, OPEN, COPY, CHEVRON
    }

    private ExplorerStyle() {}

    static Font font(int size, int weight) {
        return new Font(FONT, weight, size);
    }

    static JLabel label(String text, int size, int weight, Color color) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setFont(font(size, weight));
        label.setForeground(color);
        return label;
    }

    static JPanel panel(Color color, LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(color != null);
        if (color != null) panel.setBackground(color);
        return panel;
    }

    static Border pad(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    static JPanel surface(Color color, Color outline, int radius, LayoutManager layout) {
        JPanel panel = new JPanel(layout) {
            private static final long serialVersionUID = 1L;

            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = smooth(graphics);
                g.setColor(color);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
                if (outline != null) {
                    g.setColor(outline);
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
                }
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        panel.setOpaque(false);
        return panel;
    }

    static JScrollPane scroll(Component component, Color background) {
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(background);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        return scroll;
    }

    static JTextField field(String placeholder, int size) {
        JTextField field = new JTextField() {
            private static final long serialVersionUID = 1L;

            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (!getText().isEmpty() || placeholder.isEmpty()) return;
                Graphics2D g = smooth(graphics);
                g.setFont(getFont());
                g.setColor(FAINT);
                FontMetrics metrics = g.getFontMetrics();
                g.drawString(placeholder, getInsets().left, (getHeight() - metrics.getHeight()) / 2
                        + metrics.getAscent());
                g.dispose();
            }
        };
        field.setOpaque(false);
        field.setFont(font(size, Font.PLAIN));
        field.setForeground(INK);
        field.setCaretColor(INK);
        field.setSelectionColor(SELECTION);
        field.setBorder(pad(0, 0, 0, 0));
        return field;
    }

    static JComboBox<String> scopeSelector() {
        JComboBox<String> scope = new JComboBox<>(new String[]{"All drives", "Current folder"});
        scope.setUI(new BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                JButton arrow = new JButton(icon(Glyph.CHEVRON, 15, MUTED));
                arrow.setPreferredSize(new Dimension(25, 31));
                arrow.setBorderPainted(false);
                arrow.setContentAreaFilled(false);
                arrow.setFocusable(false);
                arrow.setOpaque(false);
                return arrow;
            }
        });
        scope.setOpaque(false);
        scope.setBorder(null);
        scope.setPreferredSize(new Dimension(131, 31));
        scope.setFont(font(12, Font.BOLD));
        scope.setForeground(INK);
        scope.setBackground(HEADER);
        scope.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int row, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, row, selected, focused);
                setBorder(pad(4, 6, 4, 5));
                setForeground(scope.isEnabled() ? INK : MUTED);
                setBackground(row < 0 ? HEADER : selected ? SELECTION : PAPER);
                return this;
            }
        });
        return scope;
    }

    static JButton button(String text, Glyph glyph, String hint, boolean primary, boolean dark) {
        JButton button = new JButton(text) {
            private static final long serialVersionUID = 1L;

            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = smooth(graphics);
                ButtonModel state = getModel();
                Color fill = primary ? (state.isRollover() ? BLUE_DARK : BLUE)
                        : dark ? (state.isRollover() || state.isSelected() ? SIDEBAR_HOVER : SIDEBAR)
                        : state.isRollover() ? SELECTION : PAPER;
                if (!isEnabled()) fill = dark ? SIDEBAR : HEADER;
                g.setColor(fill);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g.setColor(isFocusOwner() ? (dark ? TEAL : BLUE) : dark || primary ? fill : LINE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        Color color = primary ? PAPER : dark ? SIDEBAR_TEXT : INK;
        button.setIcon(icon(glyph, 17, color));
        button.setDisabledIcon(icon(glyph, 17, FAINT));
        button.setIconTextGap(9);
        button.setFont(font(13, Font.BOLD));
        button.setForeground(color);
        button.setBorder(pad(8, text.isEmpty() ? 9 : 12, 8, text.isEmpty() ? 9 : 12));
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(hint);
        button.getAccessibleContext().setAccessibleName(hint);
        if (dark) button.setHorizontalAlignment(SwingConstants.LEFT);
        return button;
    }

    static Icon icon(Glyph glyph, int size, Color color) {
        return badge(glyph, size, color, null);
    }

    static Icon badge(Glyph glyph, int size, Color color, Color fill) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }

            @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = smooth(graphics);
                g.translate(x, y);
                if (fill != null) {
                    g.setColor(fill);
                    g.fillRoundRect(0, 0, size, size, Math.max(8, size / 4), Math.max(8, size / 4));
                    int inset = size / 5;
                    g.translate(inset, inset);
                    g.scale((size - inset * 2) / 24.0, (size - inset * 2) / 24.0);
                } else {
                    g.scale(size / 24.0, size / 24.0);
                }
                draw(g, glyph, color);
                g.dispose();
            }
        };
    }

    static BufferedImage appIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        badge(Glyph.BRAND, size, SIDEBAR, TEAL).paintIcon(null, g, 0, 0);
        g.dispose();
        return image;
    }

    private static Graphics2D smooth(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    private static void draw(Graphics2D g, Glyph glyph, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (glyph) {
            case BRAND -> {
                path(g, 3, 19, 3, 7, 9, 7, 12, 10, 19, 10, 19, 12);
                path(g, 3, 19, 12, 19);
                g.drawOval(12, 11, 7, 7);
                path(g, 18, 17, 22, 21);
            }
            case SEARCH -> {
                g.drawOval(4, 4, 11, 11);
                path(g, 13, 13, 20, 20);
            }
            case DRIVE -> {
                path(g, 3, 13, 6, 5, 18, 5, 21, 13);
                g.drawRoundRect(3, 13, 18, 6, 3, 3);
                path(g, 15, 16, 17, 16);
            }
            case COMPUTER -> {
                g.drawRoundRect(3, 4, 18, 13, 3, 3);
                path(g, 12, 17, 12, 21);
                path(g, 8, 21, 16, 21);
            }
            case FOLDER -> path(g, 3, 19, 3, 6, 9, 6, 12, 9, 21, 9, 21, 19, 3, 19);
            case FILE -> {
                path(g, 6, 21, 6, 3, 14, 3, 18, 7, 18, 21, 6, 21);
                path(g, 14, 3, 14, 8, 18, 8);
                path(g, 9, 13, 15, 13);
                path(g, 9, 16, 15, 16);
            }
            case HOME -> {
                path(g, 3, 11, 12, 4, 21, 11);
                path(g, 6, 10, 6, 20, 18, 20, 18, 10);
            }
            case BACK -> { path(g, 18, 12, 5, 12); path(g, 11, 6, 5, 12, 11, 18); }
            case FORWARD -> { path(g, 6, 12, 19, 12); path(g, 13, 6, 19, 12, 13, 18); }
            case UP -> { path(g, 12, 19, 12, 5); path(g, 6, 11, 12, 5, 18, 11); }
            case REFRESH -> {
                g.drawArc(5, 5, 14, 14, 45, 295);
                path(g, 19, 4, 19, 11, 13, 11);
            }
            case CHEVRON -> path(g, 6, 9, 12, 15, 18, 9);
            case CLOSE -> { path(g, 6, 6, 18, 18); path(g, 18, 6, 6, 18); }
            case OPEN -> {
                path(g, 7, 17, 18, 6);
                path(g, 11, 6, 18, 6, 18, 13);
            }
            case COPY -> {
                g.drawRoundRect(8, 8, 12, 12, 2, 2);
                path(g, 16, 4, 4, 4, 4, 16);
            }
        }
    }

    private static void path(Graphics2D g, int... xy) {
        Path2D path = new Path2D.Float();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        g.draw(path);
    }
}
