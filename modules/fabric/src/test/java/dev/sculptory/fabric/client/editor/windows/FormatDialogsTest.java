package dev.sculptory.fabric.client.editor.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.fabric.client.editor.Translator;
import org.junit.jupiter.api.Test;

/** The Save as asset… and Export… dialogs' checks with a Format choice. */
class FormatDialogsTest {
    private final Translator tr = Translator.KEYS;

    @Test
    void aSaveTakesTheChosenFormatsExtensionWhenTheNameHasNone() {
        assertEquals("sculptory.save.target[trees/oak.litematic]",
                SaveAssetDialog.check("trees/oak", SchematicFormat.LITEMATIC, tr));
        assertEquals("sculptory.save.target[trees/oak.nbt]", SaveAssetDialog.check("trees/oak.nbt",
                SchematicFormat.STRUCTURE, tr));
        assertTrue(SaveAssetDialog.valid("oak", SchematicFormat.STRUCTURE));
        assertFalse(SaveAssetDialog.valid("oak.txt", SchematicFormat.STRUCTURE));
        assertTrue(SaveAssetDialog.check("oak.txt", SchematicFormat.SPONGE, tr).startsWith("sculptory.save.invalid"));
        assertEquals("sculptory.save.target[oak.schem]", SaveAssetDialog.check("oak", tr), "the default is .schem");
    }

    @Test
    void anExportIsNamedSafelyWithTheChosenExtension() {
        assertEquals("my_house.litematic", ExportDialog.fileName("my house.schem", SchematicFormat.LITEMATIC));
        assertEquals("igloo.nbt", ExportDialog.fileName("igloo", SchematicFormat.STRUCTURE));
        assertEquals("clipboard.schem", ExportDialog.fileName("", SchematicFormat.SPONGE));
    }
}
