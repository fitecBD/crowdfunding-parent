package abstract_modules;

import java.io.IOException;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

/**
 * Hello world!
 *
 */
public abstract class Worker implements Runnable {

	private static Logger logger = LogManager.getLogger(Worker.class);

	private static int cptProjects = 0;

	protected RetryableAction<Document, Boolean> retryableAction;

	public Worker(RetryableAction<Document, Boolean> retryableAction) throws ConfigurationException {
		super();
		this.retryableAction = retryableAction;
	}

	@Override
	public void run() {
		org.bson.Document document;
		while ((document = AppAbstract.getNextDocument()) != null) {
			logger.info("processing document #" + getCptProjects() + " " + document.getString("name") + " - "
					+ document.getInteger("id"));
			boolean noError = false;
			try {
				noError = retryableAction.run(document);
			} catch (InterruptedException e) {
				logger.error(e.getMessage() + " continuing to next document");
				e.printStackTrace();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if (!noError) {
				logger.error("error processing document : " + document.getString(
						getDocumentHumanReadableNameField() + " - " + document.getInteger(getDocumentIdField())));
			}
			incrementCptProjects();
		}
		try {
			logger.info("closing retryable action");
			retryableAction.close();
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
			e.printStackTrace();
		}
		logger.info("ENDED");
	}

	public synchronized static int getCptProjects() {
		return cptProjects;
	}

	public static synchronized void incrementCptProjects() {
		Worker.cptProjects++;
	}

	protected abstract String getDocumentIdField();

	protected abstract String getDocumentHumanReadableNameField();
}
