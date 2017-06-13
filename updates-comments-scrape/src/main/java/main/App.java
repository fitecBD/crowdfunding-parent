package main;

import java.io.IOException;

import org.apache.commons.configuration2.ex.ConfigurationException;

public class App extends AppAbstract {

	protected App(AbstractFactory<? extends Worker> workerFactory) throws ConfigurationException, IOException {
		super(workerFactory);
	}

}
