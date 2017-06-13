package main;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
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
public class InstagramWorker implements Runnable {

	private static Logger logger = LogManager.getLogger(InstagramWorker.class);

	private PropertiesConfiguration config;
	private String mongoUri;

	private String databaseName;
	private String outputCollectionName;
	private MongoCollection<org.bson.Document> mongoCollection;


	// pipeline du module Stanford Core NLP
	StanfordCoreNLP stanfordSentiementPipeline;

	private static int cptProjects = 0;

	private static int scraperIdle;

	public InstagramWorker() throws ConfigurationException {
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
		InstagramWorker.scraperIdle = config.getInt("kickstarter.idle");
	}
	
	private int countLinks(org.bson.Document authorDocument) {
		int cpt=0;
		List<String> links = authorDocument.get("external_links", ArrayList.class);
		for(String url : links){
			if(url.contains("instagram")){
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
				InstagramWorker.incrementCptProjects();
				
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
		List<org.bson.Document> instagramDataList = new ArrayList<>();
		for(String url : links){
			if(url.contains("instagram")){
				int nbRetries = 0;
				boolean retryLoop = true;
				logger.info(InstagramWorker.getCptProjects() + "-th author : " +authorDocument.getString("name"));
				do {
					try {
						org.bson.Document instagramScrapedData = getAuthorData(url);
						if(instagramScrapedData.isEmpty()){
							logger.info("pas de données récupérées pour l'auteur "+authorDocument.getString("name"));
							continue;
						}
						instagramDataList.add(instagramScrapedData);

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
		
		BasicDBObject newField = new BasicDBObject().append("instagram_data", instagramDataList);
		BasicDBObject update = new BasicDBObject().append("$set", newField);

		BasicDBObject query = new BasicDBObject();
		query.append("id", authorDocument.getInteger("id"));

		mongoCollection.updateOne(query, update, new UpdateOptions().upsert(true));
	}

	public org.bson.Document getAuthorData(String url) throws NoRetryException, IOException {
		org.bson.Document toReturn = new org.bson.Document();

		Document docJsoup;

		try {
			docJsoup = Jsoup.connect(url).get();
			JSONObject jsonObject = buildAuthorJsonData(docJsoup);
			
			if(!jsonObject.has("entry_data") || !jsonObject.getJSONObject("entry_data").has("ProfilePage")){
				String errorMessage = "no data for url "+url;
				throw new NoRetryException(errorMessage);
			}
			JSONObject authorDataJson = jsonObject.getJSONObject("entry_data").getJSONArray("ProfilePage").getJSONObject(0);
			authorDataJson = authorDataJson.getJSONObject("user");
			
			int followingCount = authorDataJson.getJSONObject("follows").getInt("count");
			toReturn.put("following_count", followingCount);
			
			int followersCount = authorDataJson.getJSONObject("followed_by").getInt("count");
			toReturn.put("followers_count", followersCount);
			
			int publicationsCount = authorDataJson.getJSONObject("media").getInt("count");
			toReturn.put("publications_count", publicationsCount);
			
//			FileUtils.writeStringToFile(new File("data.json"), authorDataJson.toString(), StandardCharsets.UTF_8);

		} catch (HttpStatusException e1) {
			logger.error(e1.getMessage());
			toReturn.put("httperror", "true");
			throw new NoRetryException(e1);
		}

		return toReturn;
	}



	private JSONObject buildAuthorJsonData(Document docJsoup) throws IOException {
		JSONObject jsonObject = null;
		Elements scriptElements = docJsoup.getElementsByTag("script");
		for(Element scriptElement : scriptElements){
			for (DataNode node : scriptElement.dataNodes()) {
				BufferedReader reader = new BufferedReader(new StringReader(node.getWholeData()));
				String line = null;
				do {
					line = reader.readLine();
					if (line != null && line.startsWith("window._sharedData")) {
						String jsonEncoded = line.substring(21, line.length() - 1);
//							String jsonDecoded = StringEscapeUtils.unescapeHtml4(jsonEncoded).replaceAll("\\\\\\\\",
//									"\\\\");
						jsonObject = new JSONObject(jsonEncoded);
					}
				} while (line != null);
				reader.close();
			}
		}
		return jsonObject;
	}

	public synchronized static int getCptProjects() {
		return cptProjects;
	}

	public static synchronized void incrementCptProjects() {
		InstagramWorker.cptProjects++;
	}

	public static void main(String ... args) throws ConfigurationException, NoRetryException, IOException {
		InstagramWorker worker = new InstagramWorker();
		worker.test();
	}

	private void test() throws IOException {
		org.bson.Document instagramScrapedData = null;
		try {
			instagramScrapedData = getAuthorData("https://www.instagram.com/idolcidigulliver/");
			
		} catch (NoRetryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		org.bson.Document updated = new org.bson.Document("scraping", instagramScrapedData);
		logger.info(instagramScrapedData);

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
