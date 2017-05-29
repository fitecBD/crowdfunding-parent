package main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.convert.DefaultListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tika.langdetect.OptimaizeLangDetector;
import org.apache.tika.language.detect.LanguageConfidence;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.Document;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.client.MongoCursor;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
import edu.stanford.nlp.util.CoreMap;

/**
 * Hello world!
 *
 */
public class Worker implements Runnable {
	private static MongoCursor<Document> cursor;
	private static PropertiesConfiguration config;
	private static String mongoUri;
	private static String databaseName;
	private static String collectionName;

	private LanguageDetector detector = new OptimaizeLangDetector();
	private StanfordCoreNLP stanfordSentiementPipeline;
	private int timeoutSentiment;

	public class WorkerData implements Callable<Boolean> {
		public StanfordCoreNLP stanfordSentiementPipeline;
		Document commentDocument;
		int[] allCommentsSentiments;

		public WorkerData(LanguageDetector detector, StanfordCoreNLP stanfordSentiementPipeline,
				Document commentDocument, int[] allCommentsSentiments) {
			super();
			this.stanfordSentiementPipeline = stanfordSentiementPipeline;
			this.commentDocument = commentDocument;
			this.allCommentsSentiments = allCommentsSentiments;
		}

		@Override
		public Boolean call() {
			String commentData = commentDocument.getString("data");
			Annotation annotation = stanfordSentiementPipeline.process(commentData);
			List<CoreMap> sentences = annotation.get(CoreAnnotations.SentencesAnnotation.class);

			int[] sentiments = { 0, 0, 0, 0, 0 };
			for (CoreMap sentence : sentences) {
				String sentiment = sentence.get(SentimentCoreAnnotations.SentimentClass.class);
				switch (sentiment) {
				case "Very negative":
					sentiments[0]++;
					allCommentsSentiments[0]++;
					break;
				case "Negative":
					sentiments[1]++;
					allCommentsSentiments[1]++;
					break;
				case "Neutral":
					sentiments[2]++;
					allCommentsSentiments[2]++;
					break;
				case "Positive":
					sentiments[3]++;
					allCommentsSentiments[3]++;
					break;
				case "Very positive":
					sentiments[4]++;
					allCommentsSentiments[4]++;
					break;
				default:
					throw new IllegalStateException(sentiment
							+ " : sentiment should be either \"Very negative\", \"Negative\", \"Neutral\", \"Positive\", \"Very positive\"");
				}
			}

			List<BsonInt32> sentimentsBson = new ArrayList<>();
			Arrays.stream(sentiments).forEach(item -> sentimentsBson.add(new BsonInt32(item)));
			commentDocument.put("sentiment",
					new BsonDocument("stanford", new BsonDocument("sentence-vector", new BsonArray(sentimentsBson))));
			if (commentDocument.containsKey("error")) {
				commentDocument.remove("error");
			}
			return true;
		}
	}

	private static Logger logger = LogManager.getLogger(Worker.class);

	private static int cptProjects = 0;

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
		this.collectionName = config.getString("mongo.collection");

		// stanford core nlp
		String[] stanfordNlpAnnotators = config.getStringArray("stanford.corenlp.annotators");
		Properties stanfordNlpProps = new Properties();
		stanfordNlpProps.setProperty("annotators", String.join(",", stanfordNlpAnnotators));
		stanfordSentiementPipeline = new StanfordCoreNLP(stanfordNlpProps);

		// miscellaneous
		timeoutSentiment = config.getInt("stanford.corenlp.timeout");

		// optimaize language detector
		try {
			detector.loadModels();
		} catch (IOException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

	public void setCursor(MongoCursor<Document> cursor) {
		this.cursor = cursor;
	}

	private synchronized Document getNextProject() {
		return (cursor.hasNext()) ? cursor.next() : null;
	}

	@Override
	public void run() {
		Document projectBson;
		try (MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoUri));) {
			while ((projectBson = App.getNextDocument()) != null) {
				logger.info("enhancing project #" + getCptProjects() + " - " + projectBson.getString("slug"));
				try {

					boolean isEnhanced = enhanceSentiment(projectBson);

					if (isEnhanced) {
						mongoUpdate(projectBson, mongoClient);
					}
				} catch (Exception e) {
					logger.error(e.getMessage(), e);
					e.printStackTrace();
				}
				incrementCptProjects();
			}
			logger.info("ENDED");
		}
	}

	private void mongoUpdate(Document projectBson, MongoClient mongoClient) {
		// update en base
		logger.info("updating project " + projectBson.getString("slug") + " in mongo");
		BasicDBObject update = new BasicDBObject();
		BasicDBObject updateFields = new BasicDBObject().append("comments",
				projectBson.get("comments", Document.class));

		update.append("$set", updateFields);

		BasicDBObject searchQuery = new BasicDBObject().append("id", projectBson.getInteger("id"));
		mongoClient.getDatabase(databaseName).getCollection(collectionName).updateOne(searchQuery, update);
	}

	private boolean enhanceSentiment(Document projectBson) {
		boolean toReturn = false;
		int[] allCommentsSentiments = { 0, 0, 0, 0, 0 };
		Document commentsDocument = projectBson.get("comments", Document.class);
		List<Document> commentsList = commentsDocument.get("data", List.class);
		int commentCount = 0;
		int commentTotalNb = commentsList.size();
		for (Document commentDocument : commentsList) {
			commentCount++;
			String commentData = commentDocument.getString("data");
			LanguageResult languageResult = detector.detect(commentData);
			if (languageResult.getConfidence().equals(LanguageConfidence.HIGH)) {
				commentDocument.put("lang",
						new BsonDocument("tika_optimaize", new BsonString(languageResult.getLanguage())));
				if ("en".equals(languageResult.getLanguage())) {
					toReturn = true;
					logger.info("comment #" + commentCount + "/" + commentTotalNb + " extracting sentiment : \""
							+ commentData + "\"");
					asyncCall(allCommentsSentiments, commentDocument);
					if (commentDocument.containsKey("error")) {
						commentsDocument.put("error", "true");
					}
				} else {
					logger.info("comment #" + commentCount + "/" + commentTotalNb
							+ " language identification : not english : \"" + commentData + "\"");
				}
			} else {
				logger.info("comment #" + commentCount + "/" + commentTotalNb
						+ " language identification : confidence not high enough for comment : \"" + commentData
						+ "\"");
			}
		}
		populateSentimentsForAllComments(allCommentsSentiments, commentsDocument);
		return toReturn;
	}

	private void asyncCall(int[] allCommentsSentiments, Document commentDocument) {
		ExecutorService pool = Executors.newFixedThreadPool(1);
		Callable<Boolean> callable = new WorkerData(detector, stanfordSentiementPipeline, commentDocument,
				allCommentsSentiments);
		try {
			Future<Boolean> future = pool.submit(callable);
			future.get(timeoutSentiment, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException e) {
			// TODO Auto-generated catch block
			logger.error(e);
			e.printStackTrace();
		} catch (TimeoutException e) {
			logger.error(e);
			commentDocument.put("error", "timeout");
		} finally {
			pool.shutdownNow();
		}
	}

	private void populateSentimentsForAllComments(int[] allCommentsSentiments, Document commentBson) {
		int[] noSentiments = { 0, 0, 0, 0, 0 };
		if (!Objects.deepEquals(allCommentsSentiments, noSentiments)) {
			List<BsonInt32> sentimentsBson = new ArrayList<>();
			Arrays.stream(allCommentsSentiments).forEach(item -> sentimentsBson.add(new BsonInt32(item)));
			commentBson.put("sentiment",
					new BsonDocument("stanford", new BsonDocument("sentence-vector", new BsonArray(sentimentsBson))));
		}
	}

	public static int getCptProjects() {
		return cptProjects;
	}

	public static synchronized void incrementCptProjects() {
		Worker.cptProjects++;
	}

	public static void main(String... args) throws ConfigurationException {
		Worker worker = new Worker();
		worker.testCallback();
	}

	private void testCallback() {
		String text = "Hi Helena, many thanks for considering cloudyBoss for your business. The team looks forward to work with you. The issue of multi-site and multiple countries management with ecommerce that you mentioned is one of the many reasons clients switch to the cloudyBoss platform. It is indeed a complex and multi-faceted issue with many cloudyBoss features to address them as per below: 1) multi-sites. Each cloudyBoss client account provides access to an integrated and comprehensive back-end from which multiple (unlimited) front-end sites can be easily configured and managed. 2) Each of these front-end sites (managed from the same back-end) come in 2 responsive flavors (mobile / desktop) with their own URL / domain names. Each will have its own default language and unique set of other languages (our native multilingual capabilities is another core reason why clients prefer cloudyBoss for the management of their content). 3) Each front-end site will also have its own default currency and unique set of multiple currencies (you did not specifically ask about this however I suspect that, similarly to other international cloudyBoss clients, you might have currency management complexities which would be solved with other features) 4) cloudyBoss has sophisticated product and pricing engines which allow you to setup all types of products and services (including complex solutions and time and/or location based products) with multiple types and levels of pricing in multiple currencies 5) The cloudyBoss product engine allows you to configure different types of freight charges depending on product route, origin and destination 6) cloudyBoss has a native universal tax engine and, while this is yet to be uploaded, we are working in configuring then maintaining overtime all known national, regional or local taxes and the rules attached to them across all countries, and pre-load this data for each client so that clients can pick and choose depending on the territories they are operating under 7) Different taxes and duties can be attached to each front-end site independently one from another, and also at product level of course 8) There are many additional features with taxes and duties to cover for any situation, such as exclusions or inclusions (dependent on origins, destinations, amounts, volumes, clients, products), calculation rules and sequencing and much more Fundamentally, the cloudyBoss standard NEXT+ solution has native product, pricing, currency, freight, tax and duty features which allow any type of international ecommerce businesses to easily configure any situations, and future-proof their configuration against upcoming changes and new national regulations with ecommerce. All from the same integrated back-end. I hope this answers your questions. Let us know otherwise and again many thanks for backing us. Cheers, Giovanni and the cloudyBoss team";
		// Object monitor;

		//		String text = "Hi i am toto and i am glad to be a computer programme";
		try (MongoClient mongoClient = new MongoClient(new MongoClientURI(mongoUri));) {
			BasicDBObject query = new BasicDBObject().append("id", new BsonInt32(2121807763));
			BasicDBObject filter = new BasicDBObject();
			filter.append("_id", 0).append("id", 1).append("comments", 1).append("slug", 1);
			Document projectBson = mongoClient.getDatabase(databaseName).getCollection(collectionName).find(query)
					.projection(filter).limit(100).noCursorTimeout(true).first();

			projectBson.get("comments", Document.class).get("data", ArrayList.class)
					.add(new Document().append("data", text));

			System.out.println((projectBson.get("comments", Document.class).get("data", ArrayList.class).get(1)));

			int[] allsentiments = { 0, 0, 0, 0, 0 };
			asyncCall(allsentiments,
					(Document) projectBson.get("comments", Document.class).get("data", ArrayList.class).get(1));

			System.out.println((projectBson.get("comments", Document.class).get("data", ArrayList.class).get(1)));
		}

		System.out.println("main end");
	}

}
