package main;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.bson.Document;

public class WorkerImpl extends Worker {

	public WorkerImpl(RetryableAction<Document, Boolean> retryableAction) throws ConfigurationException {
		super(retryableAction);
		// TODO Auto-generated constructor stub
	}

	@Override
	protected String getDocumentIdField() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected String getDocumentHumanReadableNameField() {
		// TODO Auto-generated method stub
		return null;
	}

}
