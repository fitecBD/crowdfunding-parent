package main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCursor;

/**
 * Hello world!
 *
 */
public abstract class AppAbstract {
	protected static Logger logger = LogManager.getLogger(AppAbstract.class);

	protected PropertiesConfiguration config;

	protected String mongoUri;
	protected String databaseName;
	protected String inputCollectionName;
	protected String outputCollectionName;

	// protected ProjectsStock projectsStock;
	protected List<Thread> workers = new ArrayList<>();
	protected int nbThreads;

	protected static int maxRetries;
	boolean erase;
	int limitDocument;

	protected static MongoCursor<org.bson.Document> cursor;

	protected AbstractFactory<? extends Worker> workerFactory;

	protected AppAbstract(AbstractFactory<? extends Worker> workerFactory) throws ConfigurationException, IOException {
		config = Config.getInstance();
		this.workerFactory = workerFactory;
		initFromProperties();
	}

	protected void run() throws Exception {
		try (MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoUri));) {
			BasicDBObject query = new BasicDBObject(new JSONObject(config.getString("request")).toMap());
			// BasicDBObject query =
			// BasicDBObject.parse(config.getString("request"));
			if (!erase) {
				BasicDBObject eraseFilter = new BasicDBObject(new JSONObject(config.getString("erase.filter")).toMap());
				eraseFilter.keySet().forEach(item -> query.append(item, eraseFilter.get(item)));
			}
			BasicDBObject filter = new BasicDBObject(new JSONObject(config.getString("data.filter")).toMap());
			// ((BasicDBObject)query.get("external_links")).get("$elemMatch")

			// on ajoute les ids à un hashset
			logger.info("querying projects from mongo database - "
					+ mongoClient.getDatabase(databaseName).getCollection(inputCollectionName).count(query)
					+ " documents du scrape");
			cursor = mongoClient.getDatabase(databaseName).getCollection(inputCollectionName).find(query)
					.projection(filter).limit(limitDocument).noCursorTimeout(true).iterator();

			// init des threads
			startThreads();

			// attente de la terminaison des threads
			joinThreads();
			cursor.close();
			logger.info("update terminée pour la collection " + inputCollectionName + " de la BD " + databaseName);
		}
	}

	protected void startThreads() throws Exception {
		for (int i = 0; i < nbThreads; i++) {
			Worker worker = workerFactory.newInstance();
			Thread thread = new Thread(worker, "worker #" + i);
			workers.add(thread);
			thread.start();
		}
	}

	protected void joinThreads() {
		for (Thread thread : workers) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				logger.error(e);
				e.printStackTrace();
			}
		}
	}

	protected void initFromProperties() {
		// mongo
		this.mongoUri = config.getString("mongo.uri");
		this.databaseName = config.getString("mongo.database");
		this.inputCollectionName = config.getString("mongo.collection.input");
		this.outputCollectionName = config.getString("mongo.collection.output");

		this.nbThreads = config.getInt("threads.nb");
		AppAbstract.maxRetries = config.getInt("maxRetries");
		erase = config.getBoolean("erase");
		limitDocument = config.getInt("limit");

		boolean useProxy = config.getBoolean("proxy.enable");
		if (useProxy) {
			System.setProperty("http.proxyHost", config.getString("proxy.host"));
			System.setProperty("http.proxyPort", config.getString("proxy.port"));
			logger.info("using proxy : " + config.getString("proxy.host") + ":" + config.getString("proxy.port"));
		}
	}

	public synchronized static org.bson.Document getNextDocument() {
		return cursor.tryNext();
	}

	public static int getMaxRetries() {
		return maxRetries;
	}

	public <T> T getConfig(String key, Class<T> clazz) {
		return config.get(clazz, key);
	}
}
