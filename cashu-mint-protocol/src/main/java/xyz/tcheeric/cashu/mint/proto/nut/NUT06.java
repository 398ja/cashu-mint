package xyz.tcheeric.cashu.mint.proto.nut;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.java.Log;
import xyz.tcheeric.cashu.common.model.MintInformation;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;

@Log
public class NUT06 {

    public static MintInformation info() {
        ObjectMapper objectMapper = new ObjectMapper();
        String filePath = System.getProperty("mint.json.path");
        InputStream inputStream = null;

        try {
            if (filePath != null && !filePath.isEmpty()) {
                inputStream = new FileInputStream(filePath);
            } else {
                inputStream = NUT06.class.getClassLoader().getResourceAsStream("mint.json");
            }

            if (inputStream == null) {
                throw new IOException("mint.json file not found");
            }

            return objectMapper.readValue(inputStream, MintInformation.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read mint.json", e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    // Log and ignore
                    log.log(Level.WARNING, "Failed to close input stream", e);
                }
            }
        }
    }
}