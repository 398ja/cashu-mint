package xyz.tcheeric.cashu.mint.proto.util;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;

import java.util.Properties;
import java.io.IOException;

public class YamlPropertySourceFactory implements PropertySourceFactory {

    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource resource) throws IOException {
        YamlPropertiesFactoryBean factoryBean = new YamlPropertiesFactoryBean();
        factoryBean.setResources(resource.getResource());
        factoryBean.afterPropertiesSet();

        Properties properties = factoryBean.getObject();
        if (properties == null) {
            properties = new Properties();
        }

        String sourceName = (name != null) ? name : resource.getResource().getFilename();
        if (sourceName == null || sourceName.isBlank()) {
            sourceName = resource.getResource().getDescription();
        }
        return new PropertiesPropertySource(sourceName, properties);
    }
}
