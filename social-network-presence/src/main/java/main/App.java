package main;

import java.io.IOException;

import org.apache.commons.configuration2.ex.ConfigurationException;

import abstract_modules.AppAbstract;
import implementation.WorkerFactory;

/**
 * Hello world!
 *
 */
public class App extends AppAbstract {

	protected App() throws ConfigurationException, IOException {
		super(new WorkerFactory());
	}

	public static void main(String[] args) throws Exception {
		App app = new App();
		app.run();
		System.exit(0);
	}
}
