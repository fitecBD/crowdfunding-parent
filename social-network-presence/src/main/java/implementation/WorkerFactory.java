package implementation;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.bson.Document;

import abstract_modules.AbstractFactory;
import abstract_modules.Action;
import abstract_modules.Config;
import abstract_modules.ExceptionHandler;
import abstract_modules.MongoConnector;
import abstract_modules.RetryableAction;
import abstract_modules.Worker;

public class WorkerFactory implements AbstractFactory<Worker> {

	private PropertiesConfiguration config;

	public WorkerFactory() throws ConfigurationException {
		config = Config.getInstance();
	}

	@Override
	public Worker newInstance() throws ConfigurationException {
		Action<Document, Boolean> action = new ActionImpl(new MongoConnector());
		ExceptionHandler exceptionHandler = new ExceptionHandlerImpl();
		RetryableAction<Document, Boolean> retryableAction = new RetryableAction<>(action, exceptionHandler,
				config.getInt("maxRetries"), config.getInt("idle"));
		Worker worker = new WorkerImpl(retryableAction);
		return worker;
	}

}
