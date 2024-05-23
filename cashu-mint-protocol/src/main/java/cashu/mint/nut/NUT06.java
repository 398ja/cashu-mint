package cashu.mint.nut;

import cashu.common.model.MintInformation;
import cashu.common.model.PublicKey;
import lombok.NonNull;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class NUT06 {

    public static MintInformation info() {

        Properties properties = getProperties();

        MintInformation mintInformation = new MintInformation();

        mintInformation.setName(properties.getProperty("name"));
        mintInformation.setPublicKey(PublicKey.fromString(properties.getProperty("pubkey")));
        mintInformation.setVersion(properties.getProperty("version"));
        mintInformation.setDescription(properties.getProperty("description"));
        mintInformation.setDescriptionLong(properties.getProperty("description_long"));
        mintInformation.setContacts(getContacts(properties));
        mintInformation.setMotd(properties.getProperty("motd"));
        mintInformation.setNuts(getNuts(properties));

        return mintInformation;
    }

    //contact_email=<email>
    //contact_phone=<phone>
    //contact_twitter=<value>
    //contact_nostr=<value>
    private static Map<String, String> getContacts(@NonNull Properties properties) {
        Map<String, String> contacts = new HashMap<>();
        properties.stringPropertyNames().stream().filter(key -> key.startsWith("contact_")).forEach(key -> {
            contacts.put(key.substring("contact_".length()), properties.getProperty(key));
        });
        return contacts;
    }

    //nut_<number>_method_<method_name>_info=<unit>,<min_amount>,<max_amount>
    //nut_<number>_supported=[true|false]
    private static Map<String, MintInformation.NutConfig> getNuts(@NonNull Properties properties) {
        Map<String, MintInformation.NutConfig> nuts = new HashMap<>();
        properties.stringPropertyNames().stream().filter(key -> key.startsWith("nut_")).forEach(key -> {
            String[] parts = key.split("_");
            String nutNumber = parts[1];
            String type = parts[2];
            MintInformation.NutConfig nutConfig = null;
            if (type.equals("method")) {
                nutConfig = new MintInformation.NutMethodsConfig();
                String methodName = parts[3];
                String subType = parts[4];
                MintInformation.NutMethodsConfig.Method method = new MintInformation.NutMethodsConfig.Method();
                if (subType.equals("info")) {
                    String[] values = properties.getProperty(key).split(",");
                    Map<String, String> methodInfo = new HashMap<>();
                    methodInfo.put("method", methodName);
                    methodInfo.put("unit", values[0]);
                    methodInfo.put("min_amount", values[1]);
                    methodInfo.put("max_amount", values[2]);
                    method.setMethodInfo(methodInfo);
                } else if (subType.equals("disabled")) {
                    boolean disabled = Boolean.parseBoolean(properties.getProperty(key));
                    method.setDisabled(disabled);
                }
                ((MintInformation.NutMethodsConfig) nutConfig).addMethod(method);
            } else if (type.equals("supported")) {
                boolean supported = Boolean.parseBoolean(properties.getProperty(key));
                ((MintInformation.NutSupportConfig) nutConfig).setSupported(supported);
            }
            nuts.put(nutNumber, nutConfig);
        });
        return nuts;
    }

    private static Properties getProperties() {
        Properties properties = new Properties();
        String propertiesFilePath = System.getProperty("mint.properties");

        try (InputStream input = propertiesFilePath != null ? new FileInputStream(propertiesFilePath) : NUT06.class.getClassLoader().getResourceAsStream("mint.properties")) {
            if (input == null) {
                throw new IOException("Unable to find properties file.");
            }
            properties.load(input);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to load properties file.", ex);
        }

        return properties;
    }
}
