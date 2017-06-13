package main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;

/**
 * Hello world!
 *
 */
public class Worker implements Runnable {

	private static Logger logger = LogManager.getLogger(Worker.class);

	private MongoCursor<org.bson.Document> cursor;

	private PropertiesConfiguration config;

	private String mongoUri;
	private String databaseName;
	private String outputCollectionName;
	private MongoCollection<org.bson.Document> mongoCollection;


	// pipeline du module Stanford Core NLP
	StanfordCoreNLP stanfordSentiementPipeline;

	private static int cptProjects = 0;
	
	private static int scraperIdle;

	public Worker() throws ConfigurationException {
		super();
		FileBasedConfigurationBuilder<PropertiesConfiguration> builder = new FileBasedConfigurationBuilder<PropertiesConfiguration>(
				PropertiesConfiguration.class).configure(
						new Parameters().properties().setFileName("config.properties").setThrowExceptionOnMissing(true)
						.setListDelimiterHandler(new DefaultListDelimiterHandler(';'))
						.setIncludesAllowed(false));
		this.config = builder.getConfiguration();
		initFromProperties();
	}



	private void initFromProperties() {
		// mongo
		this.mongoUri = config.getString("mongo.uri");
		this.databaseName = config.getString("mongo.database");
		this.outputCollectionName = config.getString("mongo.collection.output");
		Worker.scraperIdle = config.getInt("kickstarter.idle");
	}

	public void setCursor(MongoCursor<org.bson.Document> cursor) {
		this.cursor = cursor;
	}

	private synchronized org.bson.Document getNextProject() {
		return (cursor.hasNext()) ? cursor.next() : null;
	}

	@Override
	public void run() {
		org.bson.Document authorDocument;
		try (MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoUri));) {
			
			mongoCollection = mongoClient.getDatabase(databaseName).getCollection(outputCollectionName);
			while ((authorDocument = App.getNextDocument()) != null) {
				authorDocument=authorDocument.get("creator", org.bson.Document.class);
				Worker.incrementCptProjects();
				if(!App.alreadyScrapedAuthorsContains(authorDocument.getInteger("id"))){
					try{
						doRun(authorDocument);
					} catch (NoRetryException e) {
						String message = "no retry : " + authorDocument.getString("name");
						logger.error(message,e);
						continue;
					}
					if(scraperIdle > 0){
						try {
							logger.info("waiting "+scraperIdle+"ms");
							Thread.sleep(scraperIdle);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}else{
					logger.info("skipping : "+authorDocument.getInteger("id")+" - "+authorDocument.getString("name"));
				}
			}
			logger.info("ENDED");
		}
	}



	private void doRun(org.bson.Document authorDocument) throws NoRetryException {
		int nbRetries = 0;
		boolean retryLoop = true;
		logger.info(Worker.getCptProjects() + " authors");
		do {
			try {
				getAuthorData(authorDocument);
				mongoCollection.insertOne(authorDocument);
				App.addAlreadyScrapedAuthor(authorDocument.getInteger("id"));
				retryLoop = false;
			}catch (MongoWriteException e) {
				logger.error(e.getMessage());
				throw new NoRetryException(e);
			}catch (Exception e) {
				e.printStackTrace();
				nbRetries++;
				String message = nbRetries + "-th error getting info for author : " + authorDocument.getString("name");
				logger.error(message);
				if (nbRetries >= App.getMaxRetries()) {
					logger.error("no more retry on project : " + authorDocument.getString("name"));
					throw new NoRetryException(e);
				} else {
					try {
						Thread.sleep(1400);
					} catch (InterruptedException e1) {
						// TODO Auto-generated catch block
						logger.error(e1);
						e1.printStackTrace();
					}
				}
			}

		} while (retryLoop);
	}

	public void getAuthorData(org.bson.Document authorData) throws NoRetryException, IOException {
		String url = authorData.get("urls", org.bson.Document.class).get("web", org.bson.Document.class).getString("user")+"/about";

		Document doc;

		try {
			doc = Jsoup.connect(url).get();
			Elements navbarItems = doc.select(".nav--subnav__list .nav--subnav__item");
			for(Element item : navbarItems){
				String text = item.text();
				Elements countElement = item.select(".count");
				int count;
				count = scrapeCount(countElement);
				if(text.startsWith("Backed") || text.startsWith("Projets soutenus")){
					authorData.append("backed_count", count);
				}else if(text.startsWith("Created") || text.startsWith("Projets créés")){
					authorData.append("created_count", count);
				}else if(text.startsWith("Comments") || text.startsWith("Commentaires")){
					authorData.append("comments_count", count);
				}
			}
			
			Elements aboutElements = doc.select("#content .grid-row .col-full .menu-submenu a");
			if(!aboutElements.isEmpty()){
				authorData.append("external_links", new ArrayList<>());
				for(Element element : aboutElements){
					authorData.get("external_links", ArrayList.class).add(element.attr("href"));
				}
			}
			
			if(authorData.containsKey("created_count") && authorData.getInteger("created_count") > 0){
				authorData.append("is_creator", true);
			}
		} catch (HttpStatusException e1) {
			String message;
			switch (e1.getStatusCode()) {
			case 404:
				//TODO lever une exception custom signifiant qu'il y a eu erreur et qu'il ne faut pas retry sur cette url.
				message = url +" : la page n'existe pas";
				logger.error(message,e1);
				throw new NoRetryException(e1);

			default:
				throw e1;
			}
		}

	}



	private int scrapeCount(Elements countElement) {
		int nbProjects = 0;
		if(!countElement.isEmpty() && StringUtils.isNotBlank(countElement.text())){
			Pattern pattern = Pattern.compile("(\\d+.?\\d+)|\\d+");
			Matcher matcher = pattern.matcher(countElement.text());
			if (matcher.find()) {
				nbProjects = Integer.parseInt(matcher.group().replaceAll("\\D", ""));
			} else {
				throw new RuntimeException("nombre de projets introuvable");
			}
		}else{
			nbProjects=0;
		}
		return nbProjects;
	}

	public synchronized static int getCptProjects() {
		return cptProjects;
	}

	public static synchronized void incrementCptProjects() {
		Worker.cptProjects++;
	}
	
	public static void main(String ... args) throws ConfigurationException, NoRetryException, IOException {
		Worker worker = new Worker();
		org.bson.Document authorData = new org.bson.Document();
		authorData.append("urls", new org.bson.Document("web", new org.bson.Document("user","https://www.kickstarter.com/profile/1538673930")));
		worker.getAuthorData(authorData);
		logger.info(authorData.toJson());
	}
	
}
