package main;

public interface ExceptionHandler {
	public boolean retry(Exception e);
}
