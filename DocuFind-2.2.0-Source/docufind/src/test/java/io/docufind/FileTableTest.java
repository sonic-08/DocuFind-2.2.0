package io.docufind;

import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.*;

public final class FileTableTest {
    @Test public void sortsSizesNumericallyAndKeepsSelectionWhenAFileChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var table = new FileTable();
            var small = new FileEntry(Path.of("small.txt"), false, 900, 10);
            var large = new FileEntry(Path.of("large.txt"), false, 2048, 20);
            table.showEntries(List.of(large, small), false);
            table.getRowSorter().toggleSortOrder(4);
            table.setRowSelectionInterval(0, 0);
            assertEquals(small, table.selectedEntry());

            var changed = new FileEntry(small.path(), false, 4096, 30);
            table.showEntries(List.of(large, changed), false);
            assertEquals(changed, table.selectedEntry());
            assertEquals(1, table.getSelectedRow());

            table.showEntries(List.of(large), true);
            assertNull(table.selectedEntry());
        });
    }
}
