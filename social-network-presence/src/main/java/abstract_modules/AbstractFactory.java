package abstract_modules;

public interface AbstractFactory<T> {
	public T newInstance() throws Exception;
}
