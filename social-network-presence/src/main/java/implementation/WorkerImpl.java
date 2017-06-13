package implementation;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.bson.Document;

import abstract_modules.RetryableAction;
import abstract_modules.Worker;

public class WorkerImpl extends Worker {

	public WorkerImpl(RetryableAction<Document, Boolean> retryableAction) throws ConfigurationException {
		super(retryableAction);
		// TODO Auto-generated constructor stub
	}

	@Override
	protected String getDocumentIdField() {
		return "id";
	}

	@Override
	protected String getDocumentHumanReadableNameField() {
		return "name";
	}

}
