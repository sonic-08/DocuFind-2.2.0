package io.docufind;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import javax.swing.JOptionPane;
import javax.swing.UIManager;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public final class DocuFind {
    private DocuFind() {}

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("DocuFind needs a graphical desktop.");
            return;
        }
        Path database = databasePath();
        try {
            Files.createDirectories(database.getParent());
            var channel = FileChannel.open(database.resolveSibling("explorer.lock"), CREATE, WRITE);
            var lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                showMessage("DocuFind is already running.");
                return;
            }
            FileIndex index;
            try {
                index = new FileIndex(database);
            } catch (IOException | SQLException | RuntimeException e) {
                channel.close();
                throw e;
            }
            EventQueue.invokeLater(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                    new ExplorerWindow(index, database.getParent(), channel).open();
                } catch (IOException | ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
                    try { channel.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
                    showMessage("Could not open DocuFind. " + e.getMessage());
                }
            });
        } catch (OverlappingFileLockException e) {
            showMessage("DocuFind is already running.");
        } catch (IOException | SQLException e) {
            showMessage("Could not open the local search index. " + e.getMessage());
        }
    }

    private static Path databasePath() {
        String local = System.getenv("LOCALAPPDATA");
        Path directory = FileEntry.WINDOWS && local != null && !local.isBlank()
                ? Path.of(local, "DocuFindData")
                : Path.of(System.getProperty("user.home"), ".docufind");
        return Path.of(System.getProperty("docufind.db", directory.resolve("explorer.db").toString()))
                .toAbsolutePath().normalize();
    }

    private static void showMessage(String text) {
        EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(null, text, "DocuFind",
                JOptionPane.INFORMATION_MESSAGE));
    }
}
