package main;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import junit.framework.TestCase;

public class RetryableActionTest extends TestCase {
	protected static Logger logger = LogManager.getLogger(RetryableActionTest.class);

	/**
	 * On teste un nombre d'exceptions entre 1 et le nombre max
	 * 
	 * @throws Exception
	 */
	public void test1Exception() throws Exception {
		@SuppressWarnings("unchecked")
		Action<Void, Void> action = Mockito.mock(Action.class);
		Mockito.when(action.run(null)).then(new Answer<Void>() {
			int cpt = 0;

			@Override
			public Void answer(InvocationOnMock arg0) throws Throwable {
				cpt++;
				if (cpt <= 3) {
					throw new Exception();
				}
				System.out.println("action call #" + cpt);
				return null;
			}
		});

		ExceptionHandler exceptionHandler = Mockito.mock(ExceptionHandler.class);
		Mockito.when(exceptionHandler.retry(Mockito.any(Exception.class))).thenReturn(true);

		RetryableAction retryableAction = Mockito.spy(RetryableAction.class);
		retryableAction.setMaxRetries(5);
		retryableAction.setAction(action);
		retryableAction.setExceptionHandler(exceptionHandler);
		retryableAction.run(null);

		Mockito.verify(retryableAction, Mockito.times(1)).run(null);
		Mockito.verify(action, Mockito.times(4)).run(null);
	}
}