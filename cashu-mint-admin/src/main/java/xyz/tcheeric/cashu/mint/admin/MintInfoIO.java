package xyz.tcheeric.cashu.mint.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.model.MintInformation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@Data
public class MintInfoIO {

    private MintInformation mintInformation;

    public MintInfoIO() {
        this.mintInformation = new MintInformation();
    }

    public MintInfoIO(@NonNull MintInformation mintInformation) {
        this.mintInformation = mintInformation;
    }

    public void addContact(@NonNull MintInformation.Contact contact) {
        mintInformation.getContacts().add(contact);
    }

    public void removeContact(@NonNull MintInformation.Contact.ContactMethod method) {
        mintInformation.getContacts().removeIf(contact -> contact.getContactMethod().equals(method));
    }

    public void addNutConfig(@NonNull Integer nutNumber, @NonNull MintInformation.NutConfig nutConfig) {
        mintInformation.getNuts().put(nutNumber.toString(), nutConfig);
    }

    public void removeNutConfig(@NonNull Integer nutNumber) {
        mintInformation.getNuts().remove(nutNumber.toString());
    }

    public void removeMethod(@NonNull String nutNumber, @NonNull MintInformation.NutConfig.Method method) {
        MintInformation.NutConfig nutConfig = mintInformation.getNuts().get(nutNumber);
        if (nutConfig != null && nutConfig.getMethods() != null) {
            nutConfig.getMethods().removeIf(m -> m.equals(method));
        }
    }

    public void addMethod(@NonNull String nutNumber, @NonNull MintInformation.NutConfig.Method method) {
        MintInformation.NutConfig nutConfig = mintInformation.getNuts().get(nutNumber);
        if (nutConfig != null && nutConfig.getMethods() != null) {
            nutConfig.getMethods().add(method);
        }
    }

    public void write(@NonNull OutputStream outputStream) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writeValue(outputStream, mintInformation);
    }

    public void read(@NonNull InputStream inputStream) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        this.mintInformation = mapper.readValue(inputStream, MintInformation.class);
    }

    public static void main(String[] args) {
        try {
            MintInfoIO mintInfoIO = new MintInfoIO();
            MintInformation mintInformation = new MintInformation();
            mintInfoIO.setMintInformation(mintInformation);
            mintInfoIO.write(System.out);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
