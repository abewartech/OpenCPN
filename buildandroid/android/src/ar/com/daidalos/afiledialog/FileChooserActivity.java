package ar.com.daidalos.afiledialog;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Minimal stub for the afiledialog library.
 * The full library is not bundled; the file chooser will show a message
 * and return RESULT_CANCELED. Users can enter chart directory paths manually.
 */
public class FileChooserActivity extends Activity {
    public static final String INPUT_START_FOLDER = "input_start_folder";
    public static final String INPUT_FOLDER_MODE = "input_folder_mode";
    public static final String INPUT_SHOW_FULL_PATH_IN_TITLE = "input_show_full_path_in_title";
    public static final String INPUT_SHOW_ONLY_SELECTABLE = "input_show_only_selectable";
    public static final String INPUT_TITLE_STRING = "input_title_string";
    public static final String OUTPUT_NEW_FILE_NAME = "output_new_file_name";
    public static final String OUTPUT_FILE_OBJECT = "output_file_object";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Toast.makeText(this, "File chooser not available in this build", Toast.LENGTH_LONG).show();
        setResult(RESULT_CANCELED);
        finish();
    }
}
