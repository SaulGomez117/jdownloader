package org.jdownloader.extensions.extraction;

import org.jdownloader.extensions.extraction.translate.T;
import org.jdownloader.gui.translate._GUI;

public enum ExtractionStatus {
    NA(null),
    IDLE(null),
    RUNNING(T.T.plugins_optional_extraction_status_openingarchive2()),
    ERROR_PW(T.T.plugins_optional_extraction_status_extractfailedpass()),
    ERROR(_GUI.T.TaskColumn_getStringValue_extraction_error()),
    SUCCESSFUL(_GUI.T.TaskColumn_getStringValue_extraction_success()),
    ERROR_CRC(_GUI.T.TaskColumn_getStringValue_extraction_error_crc()),
    ERROR_NOT_ENOUGH_SPACE(_GUI.T.TaskColumn_getStringValue_extraction_error_space()),
    ERRROR_FILE_NOT_FOUND(_GUI.T.TaskColumn_getStringValue_extraction_error_file_not_found());

    private final String exp;

    private ExtractionStatus(String exp) {
        this.exp = exp;
    }

    public String getExplanation() {
        return exp;
    }
}