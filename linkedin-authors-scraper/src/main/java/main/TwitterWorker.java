package main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;

import edu.stanford.nlp.pipeline.StanfordCoreNLP;

/**
 * Hello world!
 *
 */
public class TwitterWorker implements Runnable {

	private static Logger logger = LogManager.getLogger(TwitterWorker.class);

	private PropertiesConfiguration config;
	private String mongoUri;

	private String databaseName;
	private String outputCollectionName;
	private MongoCollection<org.bson.Document> mongoCollection;


	// pipeline du module Stanford Core NLP
	StanfordCoreNLP stanfordSentiementPipeline;

	private static int cptProjects = 0;

	private static int scraperIdle;

	public TwitterWorker() throws ConfigurationException {
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
		TwitterWorker.scraperIdle = config.getInt("kickstarter.idle");
	}
	
	private int countTwitterLinks(org.bson.Document authorDocument) {
		int cpt=0;
		List<String> links = authorDocument.get("external_links", ArrayList.class);
		for(String url : links){
			if(url.contains("twitter")){
				cpt++;
			}
		}
		return cpt;
	}
	
	@Override
	public void run() {
		org.bson.Document authorDocument;
		try (MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoUri));) {
			mongoCollection = mongoClient.getDatabase(databaseName).getCollection(outputCollectionName);
			while ((authorDocument = App.getNextDocument()) != null) {
				TwitterWorker.incrementCptProjects();
				
				if(countTwitterLinks(authorDocument)>1){
					logger.info("author : "+authorDocument.getString("name")+" has more than 1 twitter links. Skipping and saving it for a next time");
				}
				
				try{
					doRun(authorDocument);
				} catch (Exception e) {
					logger.error(e.getMessage(),e);
					e.printStackTrace();
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

			}
			logger.info("ENDED");
		}
	}



	private void doRun(org.bson.Document authorDocument) {
		List<String> links = authorDocument.get("external_links", ArrayList.class);
		for(String url : links){
			if(url.contains("twitter")){
				int nbRetries = 0;
				boolean retryLoop = true;
				logger.info(TwitterWorker.getCptProjects() + "-th author : " +authorDocument.getString("name"));
				do {
					try {
						org.bson.Document twitterScrapedData = getAuthorData(url);
						org.bson.Document updated = new org.bson.Document("scraping", twitterScrapedData);


						BasicDBObject newField = new BasicDBObject().append("twitter_data", updated);
						BasicDBObject update = new BasicDBObject().append("$set", newField);



						BasicDBObject query = new BasicDBObject();
						query.append("id", authorDocument.getInteger("id"));

						mongoCollection.updateOne(query, update, new UpdateOptions().upsert(true));

						retryLoop = false;
					}catch (MongoWriteException e) {
						throw e;
					}catch (NoRetryException e) {
						logger.error("no more retry on project : " + url+" of author : " + authorDocument.getString("name"));
						retryLoop = false;
					}catch (Exception e) {
						e.printStackTrace();
						nbRetries++;
						String message = nbRetries + "-th error getting info for url : " + url+" of author : "+ authorDocument.getString("name");
						logger.error(message);
						if (nbRetries >= App.getMaxRetries()) {
							logger.error("no more retry on project : " + url+" of author : " + authorDocument.getString("name"));
							retryLoop = false;
						} else {
							try {
								Thread.sleep(2000);
							} catch (InterruptedException e1) {
								logger.error(e1);
								e1.printStackTrace();
							}
						}
					}
				} while (retryLoop);
			}
		}
	}

	public org.bson.Document getAuthorData(String url) throws NoRetryException, IOException {
		org.bson.Document toReturn = new org.bson.Document();

		Document docJsoup;

		try {
			docJsoup = Jsoup.connect(url).get();
			
			Elements nbTweetsElements = docJsoup.select(".ProfileNav-item--tweets .ProfileNav-value");
			int nbTweets;
			if(!nbTweetsElements.isEmpty() && StringUtils.isNotBlank(nbTweetsElements.text())){
				nbTweets = Integer.parseInt(nbTweetsElements.attr("data-count"));
			}else{
				nbTweets = 0;
			}
			toReturn.put("tweets_count", nbTweets);
			
			Elements nbFollowingElements = docJsoup.select(".ProfileNav-item--following .ProfileNav-value");
			int nbFollowing;
			if(!nbFollowingElements.isEmpty() && StringUtils.isNotBlank(nbFollowingElements.text())){
				nbFollowing = Integer.parseInt(nbFollowingElements.attr("data-count"));
			}else{
				nbFollowing = 0;
			}
			toReturn.put("following_count", nbFollowing);
			
			Elements nbFollowersElements = docJsoup.select(".ProfileNav-item--followers .ProfileNav-value");
			int nbFollowers;
			if(!nbFollowersElements.isEmpty() && StringUtils.isNotBlank(nbFollowersElements.text())){
				nbFollowers = Integer.parseInt(nbFollowersElements.attr("data-count"));
			}else{
				nbFollowers = 0;
			}
			toReturn.put("followers_count", nbFollowers);
			
			Elements nbLikesElements = docJsoup.select(".ProfileNav-item--favorites .ProfileNav-value");
			int nbLikes;
			if(!nbLikesElements.isEmpty() && StringUtils.isNotBlank(nbLikesElements.text())){
				nbLikes = Integer.parseInt(nbLikesElements.attr("data-count"));
			}else{
				nbLikes = 0;
			}
			toReturn.put("likes_count", nbLikes);
			
			
			Elements nbListsElements = docJsoup.select(".ProfileNav-item--lists .ProfileNav-value");
			int nbLists;
			if(!nbListsElements.isEmpty() && StringUtils.isNotBlank(nbListsElements.text())){
				nbLists = Integer.parseInt(nbListsElements.text());
			}else{
				nbLists = 0;
			}
			toReturn.put("lists_count", nbLists);

		} catch (HttpStatusException e1) {
			String message;
			switch (e1.getStatusCode()) {
			case 404:
				message = url +" : la page n'existe pas";
				logger.error(message);
				toReturn.put("http404", "true");
				break;
				
			case 503:
				logger.error(e1.getMessage(),e1);
				throw new NoRetryException(e1);
				
			default:
				throw e1;
			}
		}

		return toReturn;
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
		TwitterWorker.cptProjects++;
	}

	public static void main(String ... args) throws ConfigurationException, NoRetryException, IOException {
		TwitterWorker worker = new TwitterWorker();
		worker.test();
	}

	private void test() throws IOException {
		org.bson.Document twitterScrapedData = null;
		try {
			twitterScrapedData = getAuthorData("http://twitter.com/RnDLabsGames");
		} catch (NoRetryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		org.bson.Document updated = new org.bson.Document("scraping", twitterScrapedData);
		logger.info(twitterScrapedData);

//		try (MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoUri));) {
//			mongoCollection = mongoClient.getDatabase("crowdfunding").getCollection("authors");
//
//
//			BasicDBObject newField = new BasicDBObject().append("twitter_data", updated);
//			BasicDBObject update = new BasicDBObject().append("$set", newField);
//
//
//
//			BasicDBObject query = new BasicDBObject();
//			query.append("id", 1268497136);
//
//			mongoCollection.updateOne(query, update, new UpdateOptions().upsert(true));
//		}
	}
}
