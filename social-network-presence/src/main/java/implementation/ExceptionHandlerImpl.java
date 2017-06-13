package implementation;

import abstract_modules.ExceptionHandler;

public class ExceptionHandlerImpl implements ExceptionHandler {

	@Override
	/**
	 * Puisqu'il ne s'agit pas de scrapping, on part du principe que toute
	 * erreur est "nécessairement" critique donc pas de retry.
	 */
	public boolean retry(Exception e) {
		return false;
	}

}
