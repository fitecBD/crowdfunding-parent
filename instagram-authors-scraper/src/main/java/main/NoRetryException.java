package main;

public class NoRetryException extends Exception {

	public NoRetryException(Exception e1) {
		// TODO Auto-generated constructor stub
	}

	public NoRetryException(String errorMessage) {
		super(errorMessage);
	}

	/**
	 * 
	 */
	private static final long serialVersionUID = -2117879849927668469L;

}

