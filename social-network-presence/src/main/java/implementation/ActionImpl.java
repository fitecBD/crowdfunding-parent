package implementation;

import java.io.IOException;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.client.model.UpdateOptions;

import abstract_modules.Action;
import abstract_modules.Config;
import abstract_modules.MongoConnector;

public class ActionImpl extends Action<Document, Boolean> {
	private static Logger logger = LogManager.getLogger(ActionImpl.class);
	private PropertiesConfiguration config;
	private MongoConnector mongoConnector;
	private String socialNetworkName;
	private String documentNameField;

	public ActionImpl(MongoConnector mongoConnector) throws ConfigurationException {
		super();
		config = Config.getInstance();
		this.mongoConnector = mongoConnector;
		socialNetworkName = config.getString("socialNetwork.name");
		documentNameField = "name";
	}

	@Override
	public Boolean run(Document document) throws Exception {
		boolean noError = true;
		String key = socialNetworkName + "_data";
		try {
			if (!document.containsKey(key)) {
				BasicDBObject filter = new BasicDBObject().append("id", document.getInteger("id"));
				BasicDBObject update = new BasicDBObject().append("$set", new BasicDBObject(key, null));
				mongoConnector.getClient().getDatabase(mongoConnector.getDatabaseName())
						.getCollection(mongoConnector.getOutputCollectionName())
						.updateOne(filter, update, new UpdateOptions().upsert(true));
			} else {
				logger.info(
						"the document " + document.getString(documentNameField) + " already contains the key : " + key);
			}
		} catch (Exception e) {
			logger.error(e.getMessage());
			noError = false;
		}

		return noError;
	}

	@Override
	public void close() throws IOException {
		if (mongoConnector.getClient() != null) {
			mongoConnector.getClient().close();
		}
	}
}
