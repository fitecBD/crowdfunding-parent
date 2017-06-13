package abstract_modules;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;

public class MongoConnector {
	private PropertiesConfiguration config;

	private String mongoUri;
	private String databaseName;
	private String inputCollectionName;
	private String outputCollectionName;
	private MongoClient client;

	public MongoConnector() throws ConfigurationException {
		super();
		config = Config.getInstance();
		mongoUri = config.getString("mongo.uri");
		databaseName = config.getString("mongo.database");
		inputCollectionName = config.getString("mongo.collection.input");
		outputCollectionName = config.getString("mongo.collection.output");
		client = new MongoClient(new MongoClientURI(mongoUri));
	}

	public String getMongoUri() {
		return mongoUri;
	}

	public void setMongoUri(String mongoUri) {
		this.mongoUri = mongoUri;
	}

	public String getDatabaseName() {
		return databaseName;
	}

	public void setDatabaseName(String databaseName) {
		this.databaseName = databaseName;
	}

	public String getInputCollectionName() {
		return inputCollectionName;
	}

	public void setInputCollectionName(String inputCollectionName) {
		this.inputCollectionName = inputCollectionName;
	}

	public String getOutputCollectionName() {
		return outputCollectionName;
	}

	public void setOutputCollectionName(String outputCollectionName) {
		this.outputCollectionName = outputCollectionName;
	}

	public MongoClient getClient() {
		return client;
	}

	public void setClient(MongoClient client) {
		this.client = client;
	}

}
