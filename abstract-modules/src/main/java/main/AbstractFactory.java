
package main;

public interface AbstractFactory<T> {
	public T newInstance() throws Exception;
}
