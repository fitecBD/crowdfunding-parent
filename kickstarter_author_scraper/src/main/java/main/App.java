package main;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.StringUtils;
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
	private String inputCollectionName;
	private String outputCollectionName;
	
	private static HashSet<Integer> idsAuthorAlreadyScraped = new HashSet<>();

	// LanguageDetector detector = new OptimaizeLangDetector();

	// pipeline du module Stanford Core NLP
	StanfordCoreNLP stanfordSentiementPipeline;

	// private ProjectsStock projectsStock;
	private List<Thread> workers = new ArrayList<>();
	private int nbThreads;

	private static int maxRetries;

	private static App instance = null;

	private static MongoCursor<org.bson.Document> cursor;

	private void initAlreadyScrapedAuthorsIds() throws IOException {
		try (MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoUri));) {
			BasicDBObject query = new BasicDBObject();
//			query.append("description", new BasicDBObject("$exists", false));
			BasicDBObject filter = new BasicDBObject();
			filter.append("_id", 0).append("id", 1);
//			BasicDBObject sort = new BasicDBObject();
//			sort.append("_id", 1);

			// on ajoute les ids à un hashset
			logger.info("querying already scraped authors"
					+ mongoClient.getDatabase(databaseName).getCollection(outputCollectionName).count(query));
			cursor = mongoClient.getDatabase(databaseName).getCollection(outputCollectionName).find(query).projection(filter).noCursorTimeout(true).iterator();

			while (cursor.hasNext()) {
				org.bson.Document document = (org.bson.Document) cursor.next();
				idsAuthorAlreadyScraped.add(document.getInteger("id"));
			}
			cursor.close();
			logger.info("retrieved already scraped author from database ");
		}
	}

	public App() throws ConfigurationException, IOException {
		super();
		FileBasedConfigurationBuilder<PropertiesConfiguration> builder = new FileBasedConfigurationBuilder<PropertiesConfiguration>(
				PropertiesConfiguration.class).configure(
						new Parameters().properties().setFileName("config.properties").setThrowExceptionOnMissing(true)
								.setListDelimiterHandler(new DefaultListDelimiterHandler(';'))
								.setIncludesAllowed(false));
		this.config = builder.getConfiguration();
		initFromProperties();
		initAlreadyScrapedAuthorsIds();
		// initAlreadyCrawledProjectsIds();
	}

	public static void main(String[] args) throws JSONException, IOException, ConfigurationException {
		App app = App.getInstance();
		app.run();
	}

	private void run() throws ConfigurationException {
		try (MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoUri));) {
			BasicDBObject query = new BasicDBObject();
//			query.append("description", new BasicDBObject("$exists", false));
			BasicDBObject filter = new BasicDBObject();
			filter.append("_id", 0).append("creator", 1).append("id", 1);
//			BasicDBObject sort = new BasicDBObject();
//			sort.append("_id", 1);

			// on ajoute les ids à un hashset
			logger.info("querying projects from mongo database - "
					+ mongoClient.getDatabase(databaseName).getCollection(inputCollectionName).count(query)
					+ " documents du scrape");
			cursor = mongoClient.getDatabase(databaseName).getCollection(inputCollectionName).find(query).projection(filter).noCursorTimeout(true).iterator();

			// init des threads
			startThreads();

			// attente de la terminaison des threads
			joinThreads();
			cursor.close();
			logger.info("update terminée pour la collection " + inputCollectionName + " de la BD " + databaseName);
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
		this.inputCollectionName = config.getString("mongo.collection.input");
		this.outputCollectionName = config.getString("mongo.collection.output");

		this.nbThreads = config.getInt("threads.nb");
		App.maxRetries = config.getInt("kickstarter.maxRetries");

		boolean useProxy = config.getBoolean("proxy.enable");
		if (useProxy) {
			System.setProperty("http.proxyHost", config.getString("proxy.host"));
			System.setProperty("http.proxyPort", config.getString("proxy.port"));
			logger.info("using proxy : "+config.getString("proxy.host")+":"+config.getString("proxy.port"));
		}
	}
	
	private void getAlreadyScrapedAuthors() {
		
	}

	public static App getInstance() throws ConfigurationException, IOException {
		if (App.instance == null) {
			App.instance = new App();
		}
		return App.instance;
	}

	public synchronized static org.bson.Document getNextDocument() {
		return cursor.tryNext();
	}

	public static int getMaxRetries() {
		return maxRetries;
	}
	
	public static boolean alreadyScrapedAuthorsContains(int idAuthor){
		boolean toReturn;
		synchronized (idsAuthorAlreadyScraped) {
			toReturn = idsAuthorAlreadyScraped.contains(idAuthor);
		}
		return toReturn;
	}
	
	public static void addAlreadyScrapedAuthor(int idAuthor){
		synchronized (idsAuthorAlreadyScraped) {
			idsAuthorAlreadyScraped.add(idAuthor);
		}
	}
}
