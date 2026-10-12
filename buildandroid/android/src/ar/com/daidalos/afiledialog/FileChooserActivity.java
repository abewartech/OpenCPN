package ar.com.daidalos.afiledialog;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.Toast;

import java.io.File;

/**
 * FileChooserActivity — Storage Access Framework (SAF) implementation.
 *
 * Replaces the legacy afiledialog library with Android's native directory
 * picker (ACTION_OPEN_DOCUMENT_TREE). The selected tree URI is resolved to a
 * filesystem path where possible (primary shared storage); otherwise the URI
 * string itself is returned so the native side can handle it via SAF.
 *
 * Result contract (compatible with OCPNSettingsActivity):
 *   - RESULT_OK + OUTPUT_FILE_OBJECT (java.io.File) when a path was resolved
 *   - RESULT_OK + OUTPUT_SAF_URI (String) when only a SAF URI is available
 *   - RESULT_CANCELED when the user backs out
 */
public class FileChooserActivity extends Activity {
    // Input keys (kept for API compatibility; START_FOLDER is advisory only)
    public static final String INPUT_START_FOLDER = "input_start_folder";
    public static final String INPUT_FOLDER_MODE = "input_folder_mode";
    public static final String INPUT_SHOW_FULL_PATH_IN_TITLE = "input_show_full_path_in_title";
    public static final String INPUT_SHOW_ONLY_SELECTABLE = "input_show_only_selectable";
    public static final String INPUT_TITLE_STRING = "input_title_string";
    // Output keys
    public static final String OUTPUT_NEW_FILE_NAME = "output_new_file_name";
    public static final String OUTPUT_FILE_OBJECT = "output_file_object";
    public static final String OUTPUT_SAF_URI = "output_saf_uri";

    private static final int REQ_OPEN_TREE = 0xC4A1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        // Advisory start location is not reliably supported; launch as-is.
        try {
            startActivityForResult(i, REQ_OPEN_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "No document picker available", Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_OPEN_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                // Persist access across restarts (chart dirs must survive reboot).
                try {
                    final int takeFlags = data.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                } catch (Exception ignored) {
                }
                String path = resolveTreePath(treeUri);
                Intent result = new Intent();
                if (path != null) {
                    result.putExtra(OUTPUT_FILE_OBJECT, new File(path));
                } else {
                    result.putExtra(OUTPUT_SAF_URI, treeUri.toString());
                }
                setResult(RESULT_OK, result);
            } else {
                setResult(RESULT_CANCELED);
            }
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Resolve a SAF tree URI to a filesystem path for the common case of
     * primary shared storage (content://com.android.externalstorage.documents/tree/primary:DIR).
     * Returns null when the URI cannot be mapped to a direct path.
     */
    private String resolveTreePath(Uri treeUri) {
        try {
            if (!"com.android.externalstorage.documents".equals(treeUri.getAuthority())) {
                return null;
            }
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null) return null;
            String[] parts = docId.split(":", 2);
            if (parts.length != 2) return null;
            if ("primary".equalsIgnoreCase(parts[0])) {
                File primary = android.os.Environment.getExternalStorageDirectory();
                return new File(primary, parts[1]).getAbsolutePath();
            }
            // Secondary volumes: /storage/<volumeId>/<path>
            return new File("/storage/" + parts[0], parts[1]).getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }
}
