package xyz.tcheeric.cashu.mint.admin;

import xyz.tcheeric.cashu.mint.admin.model.MintDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.java.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;

@Setter
@Getter
@Log
public class MintIO {

    private MintDto mint;

    public void write(@NonNull OutputStream outputStream) throws IOException {
        log.log(Level.INFO, "Writing mint");
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(outputStream, mint);
    }

    public void read(@NonNull InputStream inputStream) throws IOException {
        log.log(Level.INFO, "Reading mint");
        ObjectMapper mapper = new ObjectMapper();
        this.mint = mapper.readValue(inputStream, MintDto.class);
    }

    public static void main(String[] args) {
        try {
            MintIO mintIO = new MintIO();

            Bootstrap bootstrap = new Bootstrap();
            MintDto mintDto = bootstrap.create();
            mintIO.setMint(mintDto);

            String homeDirectory = System.getProperty("user.home");
            OutputStream outputStream = new FileOutputStream(homeDirectory + "/mint.json");

            mintIO.write(outputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
