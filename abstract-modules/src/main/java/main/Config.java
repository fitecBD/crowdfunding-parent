package main;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;

public class Config {
	// private static Config instance;

	private static PropertiesConfiguration config;

	// private Config() throws ConfigurationException {
	// super();
	// FileBasedConfigurationBuilder<PropertiesConfiguration> builder = new
	// FileBasedConfigurationBuilder<PropertiesConfiguration>(
	// PropertiesConfiguration.class).configure(
	// new
	// Parameters().properties().setFileName("config.properties").setThrowExceptionOnMissing(true)
	// .setListDelimiterHandler(new DefaultListDelimiterHandler(';'))
	// .setIncludesAllowed(false));
	// this.config = builder.getConfiguration();
	// }

	public synchronized static PropertiesConfiguration getInstance() throws ConfigurationException {
		if (Config.config == null) {
			FileBasedConfigurationBuilder<PropertiesConfiguration> builder = new FileBasedConfigurationBuilder<PropertiesConfiguration>(
					PropertiesConfiguration.class)
							.configure(new Parameters().properties().setFileName("config.properties")
									.setThrowExceptionOnMissing(true)
									.setListDelimiterHandler(new DefaultListDelimiterHandler(';'))
									.setIncludesAllowed(false));
			Config.config = builder.getConfiguration();
		}
		return Config.config;
	}

	// public <T> T getParameter(String key, Class<T> clazz) {
	// return config.get(clazz, key);
	// }
}
