package main;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RetryableAction<TArg, TReturn> extends Action<TArg, TReturn> {
	protected static Logger logger = LogManager.getLogger(RetryableAction.class);
	protected Action<TArg, TReturn> action;
	protected ExceptionHandler exceptionHandler;
	protected int maxRetries;
	protected int idle;

	public RetryableAction() {
		super();
	}

	public RetryableAction(Action<TArg, TReturn> action, ExceptionHandler exceptionHandler, int maxRetries, int idle) {
		super();
		this.action = action;
		this.exceptionHandler = exceptionHandler;
		this.maxRetries = maxRetries;
		this.idle = idle;
	}

	public void setAction(Action<TArg, TReturn> action) {
		this.action = action;
	}

	public void setExceptionHandler(ExceptionHandler exceptionHandler) {
		this.exceptionHandler = exceptionHandler;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}

	public void setIdle(int idle) {
		this.idle = idle;
	}

	@Override
	public void close() throws IOException {
		action.close();
	}

	@Override
	public TReturn run(TArg arg) throws InterruptedException {
		int nbFails = 0;
		boolean retry = true;
		TReturn toReturn = null;
		do {
			try {
				toReturn = action.run(arg);
				retry = false;
			} catch (Exception e) {
				nbFails++;
				if (nbFails <= maxRetries) {
					if (!exceptionHandler.retry(e)) {
						logger.info("no retry on exception : " + e.getMessage());
						retry = false;
					} else {
						logger.error(nbFails + "-th retry : " + e.getMessage());
						Thread.sleep(idle);
					}
				} else {
					retry = false;
					logger.error("no more retry on exception : " + e.getMessage());
				}
			}
		} while (retry);
		return toReturn;
	}
}
