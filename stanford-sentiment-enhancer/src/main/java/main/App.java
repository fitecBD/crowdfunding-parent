package main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCursor;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;

/**
 * Hello world!
 *
 */
public class App {
	private static Logger logger = LogManager.getLogger(App.class);

	private PropertiesConfiguration config;

	private String mongoUri;
	private String databaseName;
	private String collectionName;

	// private ProjectsStock projectsStock;
	private List<Thread> workers = new ArrayList<>();
	private int nbThreads;
	private int maxNbComments;

	private static int maxRetries;

	public static final String ALREADY_CRAWLED_PROJECTS_FILE_PATH = "projetsOK.txt";
	private static Set<Integer> alreadyCrawledprojectsIds = new HashSet<>();

	private static App instance = null;

	private static MongoCursor<org.bson.Document> cursor;

	public App() throws ConfigurationException, IOException {
		super();
		FileBasedConfigurationBuilder<PropertiesConfiguration> builder = new FileBasedConfigurationBuilder<PropertiesConfiguration>(
				PropertiesConfiguration.class).configure(
						new Parameters().properties().setFileName("config.properties").setThrowExceptionOnMissing(true)
								.setListDelimiterHandler(new DefaultListDelimiterHandler(';'))
								.setIncludesAllowed(false));
		this.config = builder.getConfiguration();
		initFromProperties();
		// initAlreadyCrawledProjectsIds();
	}

	public static void main(String[] args) throws JSONException, IOException, ConfigurationException {
		App app = App.getInstance();
		app.run();
	}

	private void run() throws ConfigurationException {
		// ArrayList<org.bson.Document> list = new ArrayList<>();
		try (MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoUri));) {
			BasicDBObject query = new BasicDBObject();
			query.append("comments", new BasicDBObject("$exists", true));
			query.append("comments.sentiment", new BasicDBObject("$exists", false));
			if (maxNbComments > 0) {
				query.append("comments_count", new BasicDBObject("$lt", maxNbComments));
			}

			BasicDBObject filter = new BasicDBObject();
			filter.append("_id", 0).append("id", 1).append("comments", 1).append("slug", 1);
			
//			BasicDBObject sort = new BasicDBObject();
//			sort.append("comments_count", 1);

			// on ajoute les ids  un hashset
			logger.info("querying projects from mongo database - "
					+ mongoClient.getDatabase(databaseName).getCollection(collectionName).count(query)
					+ " documents du scrape");
			cursor = mongoClient.getDatabase(databaseName).getCollection(collectionName).find(query).projection(filter)
					.noCursorTimeout(true).iterator();

			// init des threads
			startThreads();

			// attente de la terminaison des threads
			joinThreads();
			cursor.close();
			logger.info("update terminée pour la collection " + collectionName + " de la BD " + databaseName);
		}
	}

	private void startThreads() throws ConfigurationException {
		for (int i = 0; i < nbThreads; i++) {
			Worker worker = new Worker();
			worker.setCursor(cursor);
			Thread thread = new Thread(worker, "worker #" + i);
			workers.add(thread);
			thread.start();
		}
	}

	private void joinThreads() {
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

	private void initFromProperties() {
		// mongo
		this.mongoUri = config.getString("mongo.uri");
		this.databaseName = config.getString("mongo.database");
		this.collectionName = config.getString("mongo.collection");

		this.nbThreads = config.getInt("threads.nb");
		this.maxRetries = config.getInt("kickstarter.maxRetries");
		this.maxNbComments = config.getInt("comments.maxnb");

		boolean useProxy = config.getBoolean("proxy.enable");
		if (useProxy) {
			System.setProperty("http.proxyHost", config.getString("proxy.host"));
			System.setProperty("http.proxyPort", config.getString("proxy.port"));
		}
	}

	public static App getInstance() throws ConfigurationException, IOException {
		if (App.instance == null) {
			App.instance = new App();
		}
		return App.instance;
	}

	public static Set<Integer> getAlreadyCrawledprojectsIds() {
		return alreadyCrawledprojectsIds;
	}

	public synchronized static org.bson.Document getNextDocument() {
		return cursor.tryNext();
	}

	public static int getMaxRetries() {
		return maxRetries;
	}

}
